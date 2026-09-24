(ns oplenario.sessoes.diplomat.http.in
  "Fronteira de IO HTTP de ENTRADA do modulo sessoes (§22.10 diplomat/http/in, ADR-0001): as rotas-dado Pedestal
  + os handlers. O diplomat e' a UNICA camada que atravessa o gate de borda: chama adapters/in (wire->models) na
  entrada e adapters/out (models->wire) na saida; o controller trabalha so em models. Le o `ator` (posto pela
  cadeia de auth em (:request :ator)), nunca fala com db/ direto (depende do Repo-Component, injetado por closure
  via `rotas`). A authz GROSSA (exige-papel) entra na rota; a FINA (policy.check) roda no controller."
  (:require [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.sessoes.adapters.in.gravacao :as adapters-in-grav]
            [oplenario.sessoes.adapters.in.incidente :as adapters-in-incidente]
            [oplenario.sessoes.adapters.in.pauta :as adapters-in-pauta]
            [oplenario.sessoes.adapters.in.assiduidade :as adapters-in-assiduidade]
            [oplenario.sessoes.adapters.in.presenca :as adapters-in-presenca]
            [oplenario.sessoes.adapters.in.sessao :as adapters-in]
            [oplenario.sessoes.adapters.in.tribuna :as adapters-in-tribuna]
            [oplenario.sessoes.adapters.out.assiduidade :as adapters-out-assiduidade]
            [oplenario.sessoes.adapters.out.folha :as adapters-out-folha]
            [oplenario.sessoes.adapters.out.gravacao :as adapters-out-grav]
            [oplenario.sessoes.adapters.out.atos-mesa :as adapters-out-atos-mesa]
            [oplenario.sessoes.adapters.out.incidente :as adapters-out-incidente]
            [oplenario.sessoes.adapters.out.pauta :as adapters-out-pauta]
            [oplenario.sessoes.adapters.out.presenca :as adapters-out-presenca]
            [oplenario.sessoes.adapters.out.sessao :as adapters-out]
            [oplenario.sessoes.adapters.out.tribuna :as adapters-out-tribuna]
            [oplenario.sessoes.controllers :as controllers])
  (:import (org.postgresql.util PSQLException)))

(set! *warn-on-reflection* true)

(def ^:private ^:const max-upload-bytes
  "Teto do corpo binario da ingestao de gravacao (2 GiB). O `corpo-json` (256 KiB) nao incide aqui (corpo
  binario), mas um upload ilimitado seria DoS de storage (review sec MAJOR). Estouro -> 413. (Limite mais
  fino + retomada/dedup = utilitario CLI da §16.4, carry de infra.)"
  (* 2 1024 1024 1024))

(defn- limitar-stream
  "Envolve o InputStream contando os bytes lidos; estoura :corpo/grande ao exceder `limite` (nunca deixa o
  store gravar alem do teto). Estende InputStream e fecha sobre `in` type-hinted (sem proxy-super = sem
  reflexao). Cobre os 3 `read` + close/available que o SDK do MinIO pode usar."
  ^java.io.InputStream [^java.io.InputStream in ^long limite]
  (let [lido   (java.util.concurrent.atomic.AtomicLong. 0)
        checar (fn [n] (when (and (pos? (long n)) (> (.addAndGet lido (long n)) limite))
                         (throw (ex-info "upload grande demais" {:tipo :corpo/grande})))
                 n)]
    (proxy [java.io.InputStream] []
      (read
        ([]            (let [b (.read in)] (checar (if (neg? b) 0 1)) b))
        ([buf]         (checar (.read in ^bytes buf)))
        ([buf off len] (checar (.read in ^bytes buf (int off) (int len)))))
      (available [] (.available in))
      (close [] (.close in)))))

(defn- listar-handler
  "GET /sessoes — a LISTAGEM GERAL das sessoes do ente (ledger de prontidao #16, MATA): a home do vereador
  so' tinha POST /sessoes (agendar) e GET /sessoes/:id (uma so'), entao o frontend sempre recebia
  `sessoes=[]` e tratava 'nao sei' como 'nao ha' (carry documentado em
  `apps/frontend/src/lib/meu-painel-vista.ts:34-35` e no comentario de topo de
  `apps/frontend/src/app/(vereador)/vereador/page.tsx`) — mesmo com uma sessao ABERTA e uma AGENDADA
  existindo ao MESMO tempo, que `/paineis/mesa` mostrava corretamente.

  SEM papel exigido na borda — MESMO nivel de authz de `/quorum`/`/composicao`/`/tribuna`: a authz FINA
  (`logic/pode-ver-quorum-da-sessao?`) roda no controller, POR LINHA (nunca um `check!` unico na entrada —
  ver a docstring de `controllers/listar-sessoes`: uma listagem que barrasse a requisicao inteira por
  causa de UMA sessao secreta erraria na direcao oposta, escondendo as PUBLICAS de quem so' nao pode ver
  a secreta).

  SEM 404/409: a listagem geral nunca falha por 'sessao nao encontrada' (nao ha' `:id` no path) nem por
  'sessao sem data' (isso e' invariante de UMA sessao — `/quorum`/`/composicao` — nao da listagem). O
  UNICO jeito de esta rota falhar e' o teto de linhas (`logic/teto-de-sessoes-da-listagem-geral`), mapeado
  a 422 pelo interceptor global `erro` — SEM try/catch aqui, mesmo racional simples do `assiduidade-handler`."
  [repo-sessoes]
  (fn [req]
    (http/json-resposta 200 (adapters-out/sessoes->wire (controllers/listar-sessoes repo-sessoes (:ator req))))))

(defn- buscar-handler
  "GET /sessoes/:id. adapters/in coage o :id; controller carrega+autoriza; adapters/out projeta. nil -> 404."
  [repo-sessoes]
  (fn [req]
    (let [ator (:ator req)
          id   (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-let [s (controllers/buscar-sessao repo-sessoes ator id)]
        (http/json-resposta 200 (adapters-out/sessao->wire s))
        (http/json-resposta 404 {:erro "sessao nao encontrada"})))))

(defn- transicionar-handler
  "POST /sessoes/:id/transicao (Mesa de conducao). adapters/in coage o :id + valida o corpo {para, lock-version,
  motivo?}; o controller carrega+autoriza a sessao e transiciona (o Repo emite sessao.transicionou na mesma tx);
  adapters/out projeta o recibo. nil (sessao inexistente) -> 404; transicao invalida pela maquina / lock-stale ->
  409 (nao 500). 200 (atualiza a sessao existente, nao cria)."
  [repo-sessoes]
  (fn [req]
    (let [ator (:ator req)
          m    (adapters-in/transicionar->dominio (get-in req [:path-params :id]) (:json-params req))]
      (try
        (if-let [recibo (controllers/transicionar-sessao repo-sessoes ator m)]
          (http/json-resposta 200 (adapters-out/recibo-transicao->wire recibo))
          (http/json-resposta 404 {:erro "sessao nao encontrada"}))
        (catch clojure.lang.ExceptionInfo e
          (if (= :conflito/transicao (:tipo (ex-data e)))
            (http/json-resposta 409 {:erro "transicao de estado invalida ou lock-version desatualizado"})
            (throw e)))))))

(defn- resposta-conflito-presenca
  "Traduz `:conflito/sessao-nao-aceita-presenca` -> 409 com a mensagem DO DOMINIO, propagando o que a ex-data
  carrega para a recusa ser ACIONAVEL: `vereador-id` (qual linha reprovou) e `indice` (a POSICAO da linha no
  lote — o cliente casa `recibos[i]` com `registros[i]` por posicao).

  Antes disso, o Repo ja enriquecia a ex-data com `:vereador-id` e a borda o DESCARTAVA: um 409 de lote de 21
  nomes dizia so' 'o instante informado ...', sem dizer qual dos 21, e o secretario fazia busca binaria
  reenviando sublotes ao vivo. Espelha `resposta-conflito-justificativa`, escrita com o mesmo racional."
  [e]
  (let [d (ex-data e)]
    (http/json-resposta 409 (cond-> {:erro (ex-message e)}
                              (:vereador-id d) (assoc :vereador-id (str (:vereador-id d)))
                              (:indice d)      (assoc :indice (:indice d))))))

(defn- resposta-conflito-sessao-fechada
  "Traduz `controllers/exigir-sessao-aberta!` (`:conflito/sessao-fechada`) -> 409 com a mensagem DO DOMINIO
  (T2 grupo A achado #4/#5, ledger de prontidao Fase 8: sessao ENCERRADA aceitava POST de item de pauta e
  abertura de votacao — nenhum controller de conducao checava `estado`). UMA fn so', reusada pelas 11 rotas
  de escrita de conducao (pauta/tribuna/decisao-mesa/incidente/vinculo-gravacao) — mesma disciplina de
  `resposta-conflito-presenca`/`resposta-conflito-justificativa` acima."
  [e]
  (http/json-resposta 409 {:erro (ex-message e)}))

(defn- registrar-presenca-handler
  "POST /sessoes/:id/presenca (§22.6 eixo C). adapters/in coage o :id + valida o corpo {vereador-id, tipo,
  modalidade, ocorrido-em}; o controller carrega+autoriza a sessao e grava o evento append-only (a fonte e'
  forcada = manual_secretaria; o Repo emite presenca.registrada na mesma tx); adapters/out projeta o recibo.
  nil (sessao inexistente) -> 404; sucesso -> 201 (cria um evento — append-only, sem CAS).

  409 quando o Repo recusa (`:conflito/sessao-nao-aceita-presenca`): sessao que ja fechou, ou hora declarada
  fora da janela da sessao / no futuro. E' 409 e nao 403 (nao e' falta de permissao — o mesmo secretario podia
  ter gravado isto ha' um minuto) e nao 400 (o corpo esta bem formado; o que mudou foi o ESTADO do recurso).
  A mensagem vem do dominio (`logic/mensagem-de-recusa-de-presenca`) porque so' ele sabe qual limite foi
  violado; e' texto nosso, sem dado de pessoa. Qualquer outro `:tipo` re-lanca (500 opaco, fail-closed).

  O `roster-da-casa` chega aqui (revisao) porque o `vereador-id` do corpo passou a ser validado contra a
  composicao da Casa na data da sessao — sem isso a rota gravava qualquer UUID como presente."
  [repo-sessoes roster-da-casa relogio]
  (fn [req]
    (let [ator (:ator req)
          m    (adapters-in-presenca/registrar-presenca->dominio (get-in req [:path-params :id]) (:json-params req))]
      (try
        (if-let [recibo (controllers/registrar-presenca repo-sessoes roster-da-casa ator m (tempo/agora relogio))]
          (http/json-resposta 201 (adapters-out-presenca/recibo-presenca->wire recibo))
          (http/json-resposta 404 {:erro "sessao nao encontrada"}))
        (catch clojure.lang.ExceptionInfo e
          (case (:tipo (ex-data e))
            :conflito/sessao-nao-aceita-presenca (resposta-conflito-presenca e)
            :conflito/sessao-sem-data
            (http/json-resposta 409 {:erro "sessao sem data marcada: informe a data da sessao antes de registrar presenca"})
            (throw e)))))))

(defn- registrar-presenca-lote-handler
  "POST /sessoes/:id/presenca/lote (§22.6 eixo C, Etapa 2c). adapters/in coage o :id + valida o corpo
  {registros: [...]} — MESMO shape do POST unitario por linha, teto de `wire/teto-lote-presenca` linhas
  (excedido -> 400), vereador repetido no mesmo lote -> 400; o controller carrega+autoriza a sessao e grava o
  LOTE INTEIRO numa UNICA transacao no Repo (o gate de estado + a janela da hora valem para CADA linha — a
  primeira reprovada recusa o lote todo, nenhuma linha grava). nil (sessao inexistente) -> 404; sucesso -> 201
  com os N recibos, na ordem do corpo.

  409 quando o Repo recusa (`:conflito/sessao-nao-aceita-presenca`) — MESMA semantica do POST unitario,
  aplicada ao lote inteiro: nenhuma linha entrou. O corpo do 409 diz QUAL linha reprovou (`vereador-id` +
  `indice`), senao a tela nao consegue destaca-la num lote de dezenas de nomes."
  [repo-sessoes roster-da-casa relogio]
  (fn [req]
    (let [ator (:ator req)
          m    (adapters-in-presenca/registrar-presenca-lote->dominio
                (get-in req [:path-params :id]) (:json-params req))]
      (try
        (if-let [recibos (controllers/registrar-presenca-lote repo-sessoes roster-da-casa ator m
                                                             (tempo/agora relogio))]
          (http/json-resposta 201 (adapters-out-presenca/recibos-presenca-lote->wire recibos))
          (http/json-resposta 404 {:erro "sessao nao encontrada"}))
        (catch clojure.lang.ExceptionInfo e
          (case (:tipo (ex-data e))
            :conflito/sessao-nao-aceita-presenca (resposta-conflito-presenca e)
            :conflito/sessao-sem-data
            (http/json-resposta 409 {:erro "sessao sem data marcada: informe a data da sessao antes de registrar presenca"})
            (throw e)))))))

(defn- confirmar-presenca-handler
  "POST /sessoes/:id/presenca/confirmar (Onda C3, papel 'vereador'). Sem corpo — `vereador-id` resolvido do
  ator (anti-forja), `fonte`/`tipo`/`modalidade` fixos no controller, `ocorrido-em` = o relogio do servidor
  (nunca do cliente). Reusa o MESMO wire/out de recibo que a rota da Mesa (`recibo-presenca->wire`).
  nil (sessao inexistente OU ator sem cadastro de vereador) -> 404.

  O MESMO 409 da rota da Mesa: uma porta self-service sem o gate seria o buraco por outra fechadura — e isso
  passou a valer tambem para o gate de ASSENTO (ter cadastro nao e' ter cadeira na data; um ex-vereador
  continua resolvendo por identidade)."
  [repo-sessoes roster-da-casa resolver-vereador relogio]
  (fn [req]
    (let [ator (:ator req)
          sid  (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          instante (tempo/agora relogio)]
      (try
        (if-let [recibo (controllers/confirmar-minha-presenca repo-sessoes roster-da-casa resolver-vereador
                                                             ator sid instante)]
          (http/json-resposta 201 (adapters-out-presenca/recibo-presenca->wire recibo))
          (http/json-resposta 404 {:erro "sessao nao encontrada, ou vereador sem cadastro vinculado neste ente"}))
        (catch clojure.lang.ExceptionInfo e
          (case (:tipo (ex-data e))
            :conflito/sessao-nao-aceita-presenca (resposta-conflito-presenca e)
            :conflito/sessao-sem-data
            (http/json-resposta 409 {:erro "sessao sem data marcada: informe a data da sessao antes de confirmar presenca"})
            (throw e)))))))

;; ---------- §22.6 eixo C — justificativa de ausencia (Etapa 2 da chamada) ----------

(defn- resposta-conflito-justificativa
  "Traduz `:conflito/justificativa` -> 409 com a mensagem DO DOMINIO (`logic/mensagem-de-recusa-de-justificativa`,
  ja' embutida na ex-message) — mesmo contrato do 409 do gate de presenca: so' o dominio sabe qual limite foi
  violado, e a mensagem e' texto nosso, sem dado de pessoa (NUNCA o `motivo`). Quando o conflito e' 'ja existe',
  devolve tambem `justificativa-id` para a tela poder ABRIR a existente em vez de so' avisar. Qualquer outro
  `:tipo` re-lanca (500 opaco, fail-closed)."
  [e]
  (let [d (ex-data e)]
    (http/json-resposta 409 (cond-> {:erro (ex-message e)}
                              (:justificativa-id d) (assoc :justificativa-id (str (:justificativa-id d)))))))

(defn- abrir-justificativa-handler
  "POST /sessoes/:id/justificativas (papel 'secretario' — a Mesa protocola em nome do vereador). adapters/in
  coage o :id + valida o corpo {vereador-id, motivo}; o controller carrega+autoriza a sessao, exige que o
  vereador tenha assento na data da sessao e cria o ato 'pendente'; adapters/out projeta o recibo. nil (sessao
  inexistente) -> 404; sem assento / justificativa ja existente -> 409; sucesso -> 201."
  [repo-sessoes roster-da-casa]
  (fn [req]
    (let [ator (:ator req)
          m    (adapters-in-presenca/abrir-justificativa->dominio (get-in req [:path-params :id])
                                                                   (:json-params req))]
      (try
        (if-let [recibo (controllers/abrir-justificativa repo-sessoes roster-da-casa ator m)]
          (http/json-resposta 201 (adapters-out-presenca/justificativa-aberta->wire recibo))
          (http/json-resposta 404 {:erro "sessao nao encontrada"}))
        (catch clojure.lang.ExceptionInfo e
          (case (:tipo (ex-data e))
            :conflito/justificativa (resposta-conflito-justificativa e)
            :conflito/sessao-sem-data
            (http/json-resposta 409 {:erro "sessao sem data marcada: informe a data da sessao antes de lancar justificativas"})
            (throw e)))))))

(defn- abrir-minha-justificativa-handler
  "POST /sessoes/:id/minha-justificativa (papel 'vereador' — self-service). Corpo so' {motivo}: `vereador-id`
  NAO existe no allowlist e e' resolvido da IDENTIDADE do ator (anti-forja, mesmo contrato de
  `/presenca/confirmar`). nil (sessao inexistente OU ator sem cadastro de vereador vinculado) -> 404; sem
  assento na data / ja existente -> 409; sucesso -> 201."
  [repo-sessoes roster-da-casa resolver-vereador]
  (fn [req]
    (let [ator (:ator req)
          m    (adapters-in-presenca/abrir-minha-justificativa->dominio (get-in req [:path-params :id])
                                                                        (:json-params req))]
      (try
        (if-let [recibo (controllers/abrir-minha-justificativa repo-sessoes roster-da-casa resolver-vereador ator m)]
          (http/json-resposta 201 (adapters-out-presenca/justificativa-aberta->wire recibo))
          (http/json-resposta 404 {:erro "sessao nao encontrada, ou vereador sem cadastro vinculado neste ente"}))
        (catch clojure.lang.ExceptionInfo e
          (case (:tipo (ex-data e))
            :conflito/justificativa (resposta-conflito-justificativa e)
            :conflito/sessao-sem-data
            (http/json-resposta 409 {:erro "sessao sem data marcada: informe a data da sessao antes de lancar justificativas"})
            (throw e)))))))

(defn- listar-justificativas-handler
  "GET /sessoes/:id/justificativas (papel 'secretario'). adapters/in coage o :id; o controller carrega+autoriza
  a sessao e lista os atos; adapters/out projeta (com o `lock-version`, que e' insumo da decisao). nil -> 404.
  Exige papel na BORDA — nao e' read-model publico: `motivo` pode carregar dado de saude (LGPD)."
  [repo-sessoes]
  (fn [req]
    (let [ator (:ator req)
          id   (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-let [r (controllers/justificativas-da-sessao repo-sessoes ator id)]
        (http/json-resposta 200 (adapters-out-presenca/justificativas->wire r))
        (http/json-resposta 404 {:erro "sessao nao encontrada"})))))

(defn- decidir-justificativa-handler
  "PATCH /sessoes/:id/justificativas/:jid/decisao (papel 'secretario' — o ato da Mesa). adapters/in coage os
  path-params + valida o corpo {estado, lock-version} (so' os terminais; CAS obrigatorio -> 400 se ausente); o
  controller carrega+autoriza a sessao, exige que o ato seja DESTA sessao, barra o juiz-em-causa-propria (403
  via kernel/autorizacao) e decide (CAS + maquina). nil (sessao/justificativa ausente ou de outra sessao) ->
  404; lock-stale / ja terminal / inexistente -> 409 (nao 500). 200 (atualiza o ato, nao cria)."
  [repo-sessoes resolver-vereador]
  (fn [req]
    (let [ator (:ator req)
          m    (adapters-in-presenca/decidir-justificativa->dominio (get-in req [:path-params :id])
                                                                     (get-in req [:path-params :jid])
                                                                     (:json-params req))]
      (try
        (if-let [recibo (controllers/decidir-justificativa repo-sessoes resolver-vereador ator m)]
          (http/json-resposta 200 (adapters-out-presenca/justificativa-decidida->wire recibo))
          (http/json-resposta 404 {:erro "justificativa nao encontrada nesta sessao"}))
        (catch clojure.lang.ExceptionInfo e
          (if (= :conflito/justificativa (:tipo (ex-data e)))
            (resposta-conflito-justificativa e)
            (throw e)))))))

(defn- inscrever-handler
  "POST /sessoes/:id/inscricoes (§22.6 eixo F, tribuna). adapters/in coage o :id + valida o corpo {vereador-id,
  origem-inscricao, fase, proposicao-ref-id?}; o controller carrega+autoriza a sessao e inscreve (o Repo emite
  inscricao.registrada na mesma tx); adapters/out projeta o recibo {:id :ordem}. nil -> 404; sessao ja fechada
  (`:conflito/sessao-fechada`) -> 409 (ledger Fase 8 achado #4/#5); sucesso -> 201."
  [repo-sessoes]
  (fn [req]
    (let [ator (:ator req)
          m    (adapters-in-tribuna/inscrever->dominio (get-in req [:path-params :id]) (:json-params req))]
      (try
        (if-let [recibo (controllers/inscrever-orador repo-sessoes ator m)]
          (http/json-resposta 201 (adapters-out-tribuna/recibo-inscricao->wire recibo))
          (http/json-resposta 404 {:erro "sessao nao encontrada"}))
        (catch clojure.lang.ExceptionInfo e
          (if (= :conflito/sessao-fechada (:tipo (ex-data e)))
            (resposta-conflito-sessao-fechada e)
            (throw e)))))))

(defn- desistir-handler
  "POST /sessoes/:id/inscricoes/:insc-id/desistir (§22.6 eixo F, tribuna). adapters/in coage os path-params + o
  corpo {lock-version}; o controller carrega+autoriza a sessao e desiste (CAS + maquina; o Repo emite
  inscricao.desistida na mesma tx); adapters/out projeta o recibo {:de :para}. nil (sessao inexistente) -> 404;
  ja-desistiu / lock-stale / inscricao inexistente -> 409 (nao 500). 200 (atualiza, nao cria)."
  [repo-sessoes]
  (fn [req]
    (let [ator (:ator req)
          m    (adapters-in-tribuna/desistir->dominio (get-in req [:path-params :id])
                                                      (get-in req [:path-params :insc-id])
                                                      (:json-params req))]
      (try
        (if-let [recibo (controllers/desistir-inscricao repo-sessoes ator m)]
          (http/json-resposta 200 (adapters-out-tribuna/recibo-desistencia->wire recibo))
          (http/json-resposta 404 {:erro "sessao nao encontrada"}))
        (catch clojure.lang.ExceptionInfo e
          (case (:tipo (ex-data e))
            :conflito/inscricao
            (http/json-resposta 409 {:erro "inscricao ja desistida, inexistente ou lock-version desatualizado"})
            :conflito/sessao-fechada (resposta-conflito-sessao-fechada e)
            (throw e)))))))

(defn- iniciar-fala-handler
  "POST /sessoes/:id/falas (§22.6 eixo F, tribuna execucao). adapters/in coage o :id + valida o corpo {orador-id,
  tipo-fala, fase, iniciou-em, ...?}; o controller carrega+autoriza a sessao e inicia a fala (o Repo loga
  'iniciada' + emite fala.iniciada na mesma tx); adapters/out projeta o recibo {:fala-id}. nil -> 404; sessao ja
  fechada (`:conflito/sessao-fechada`) -> 409 (ledger Fase 8 achado #4/#5); sucesso -> 201 (cria)."
  [repo-sessoes]
  (fn [req]
    (let [ator (:ator req)
          m    (adapters-in-tribuna/iniciar-fala->dominio (get-in req [:path-params :id]) (:json-params req))]
      (try
        (if-let [recibo (controllers/iniciar-fala repo-sessoes ator m)]
          (http/json-resposta 201 (adapters-out-tribuna/recibo-fala-iniciada->wire recibo))
          (http/json-resposta 404 {:erro "sessao nao encontrada"}))
        (catch clojure.lang.ExceptionInfo e
          (if (= :conflito/sessao-fechada (:tipo (ex-data e)))
            (resposta-conflito-sessao-fechada e)
            (throw e)))))))

(defn- cronometro-handler
  "POST /sessoes/:id/falas/:fala-id/cronometro (§22.6 eixo F). adapters/in coage os path-params + valida o corpo
  {tipo, ocorrido-em, segundos-adicionais?} INCL. a coerencia tipo<->segundos (-> 400 na borda); o controller
  carrega+autoriza a sessao e registra o evento append-only (o Repo emite fala.cronometro na mesma tx);
  adapters/out projeta o recibo {:id}. nil (sessao inexistente) -> 404; sessao ja fechada
  (`:conflito/sessao-fechada`) -> 409 (ledger Fase 8 achado #4/#5); sucesso -> 201 (cria evento)."
  [repo-sessoes]
  (fn [req]
    (let [ator (:ator req)
          m    (adapters-in-tribuna/cronometro->dominio (get-in req [:path-params :id])
                                                        (get-in req [:path-params :fala-id])
                                                        (:json-params req))]
      (try
        (if-let [recibo (controllers/registrar-evento-cronometro repo-sessoes ator m)]
          (http/json-resposta 201 (adapters-out-tribuna/recibo-cronometro->wire recibo))
          (http/json-resposta 404 {:erro "sessao nao encontrada"}))
        (catch clojure.lang.ExceptionInfo e
          (if (= :conflito/sessao-fechada (:tipo (ex-data e)))
            (resposta-conflito-sessao-fechada e)
            (throw e)))))))

(defn- encerrar-fala-handler
  "POST /sessoes/:id/falas/:fala-id/encerrar (§22.6 eixo F). adapters/in coage os path-params + valida o corpo
  {encerrou-em, lock-version}; o controller carrega+autoriza a sessao e encerra a fala (CAS + computa o tempo
  dos eventos; o Repo emite fala.encerrada na mesma tx); adapters/out projeta o recibo {:fala-id :tempo-segundos}.
  nil (sessao inexistente) -> 404; ja-encerrada / lock-stale / fala inexistente -> 409 (nao 500). 200 (atualiza)."
  [repo-sessoes]
  (fn [req]
    (let [ator (:ator req)
          m    (adapters-in-tribuna/encerrar-fala->dominio (get-in req [:path-params :id])
                                                           (get-in req [:path-params :fala-id])
                                                           (:json-params req))]
      (try
        (if-let [recibo (controllers/encerrar-fala repo-sessoes ator m)]
          (http/json-resposta 200 (adapters-out-tribuna/recibo-fala-encerrada->wire recibo))
          (http/json-resposta 404 {:erro "sessao nao encontrada"}))
        (catch clojure.lang.ExceptionInfo e
          (case (:tipo (ex-data e))
            :conflito/fala
            (http/json-resposta 409 {:erro "fala ja encerrada, inexistente ou lock-version desatualizado"})
            :conflito/sessao-fechada (resposta-conflito-sessao-fechada e)
            (throw e)))))))

(defn- decisao-mesa-handler
  "POST /sessoes/:id/decisoes-mesa (§22.6 eixo F, tribuna). adapters/in coage o :id + valida o corpo {questao,
  decisao, decidido-em, presidente-id, fundamentacao?, fala-id?} INCL. o nao-vazio de questao/decisao (-> 400 na
  borda); o controller carrega+autoriza a sessao, exige que o presidente componha a Casa na data da sessao (seam
  `roster-da-casa`), injeta created-by do ator e registra a decisao append-only (sem CAS, sem evento; se fala-id
  veio, tem de ser desta sessao -> senao 404); adapters/out projeta o recibo {:id}. nil (sessao inexistente /
  fala alheia) -> 404; sessao ja fechada (`:conflito/sessao-fechada`) -> 409 (ledger Fase 8); presidente fora da
  composicao (`:conflito/decisao-mesa`, docs/23) -> 409; sucesso -> 201 (cria o ato)."
  [repo-sessoes roster-da-casa]
  (fn [req]
    (let [ator (:ator req)
          m    (adapters-in-tribuna/decisao-mesa->dominio (get-in req [:path-params :id]) (:json-params req))]
      (try
        (if-let [recibo (controllers/registrar-decisao-mesa repo-sessoes roster-da-casa ator m)]
          (http/json-resposta 201 (adapters-out-tribuna/recibo-decisao-mesa->wire recibo))
          (http/json-resposta 404 {:erro "sessao nao encontrada"}))
        (catch clojure.lang.ExceptionInfo e
          (case (:tipo (ex-data e))
            :conflito/sessao-fechada (resposta-conflito-sessao-fechada e)
            :conflito/decisao-mesa   (http/json-resposta 409 {:erro (ex-message e)})
            :conflito/sessao-sem-data
            (http/json-resposta 409 {:erro "sessao sem data marcada: informe a data da sessao antes de registrar a decisao"})
            (throw e)))))))

(defn- atos-mesa-handler
  "GET /sessoes/:id/atos-mesa (docs/23 Fatia 2, papel 'secretario'). As decisoes da Mesa e os incidentes da
  sessao, cada lista em ordem cronologica — a leitura que o cockpit da Mesa usa para mostrar o que ja foi
  registrado. Exige papel na BORDA: e' leitura operacional da Mesa, nao read-model publico. :id malformado ->
  400; sessao inexistente -> 404; outra Casa -> 403 (camada fina no controller)."
  [repo-sessoes]
  (fn [req]
    (let [sessao-id (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-let [atos (controllers/listar-atos-mesa repo-sessoes (:ator req) sessao-id)]
        (http/json-resposta 200 (adapters-out-atos-mesa/atos-mesa->wire atos))
        (http/json-resposta 404 {:erro "sessao nao encontrada"})))))

(defn- incidente-handler
  "POST /sessoes/:id/incidentes (§16.13). adapters/in coage o :id + valida o corpo {tipo, resultado, descricao,
  ocorrido-em, objeto-tipo?, objeto-id?, requerente-id?, deliberacao?} INCL. o enum/nao-vazio/coerencia (-> 400 na
  borda); o controller carrega+autoriza a sessao, injeta created-by do ator e registra o incidente append-only +
  emite incidente.registrado (SSE) na mesma tx; adapters/out projeta o recibo {:id}. nil (sessao inexistente) ->
  404; sessao ja fechada (`:conflito/sessao-fechada`) -> 409 (ledger Fase 8); sucesso -> 201 (cria o ato —
  append-only, sem outro 409)."
  [repo-sessoes]
  (fn [req]
    (let [ator (:ator req)
          m    (adapters-in-incidente/registrar->dominio (get-in req [:path-params :id]) (:json-params req))]
      (try
        (if-let [recibo (controllers/registrar-incidente repo-sessoes ator m)]
          (http/json-resposta 201 (adapters-out-incidente/recibo-incidente->wire recibo))
          (http/json-resposta 404 {:erro "sessao nao encontrada"}))
        (catch clojure.lang.ExceptionInfo e
          (if (= :conflito/sessao-fechada (:tipo (ex-data e)))
            (resposta-conflito-sessao-fechada e)
            (throw e)))))))

(defn- adicionar-item-handler
  "POST /sessoes/:id/pauta/itens (§22.6 eixo B). adapters/in coage o :id + valida o corpo {fase, tipo-item,
  proposicao-id? | texto-descricao?} INCL. a FK-por-tipo (-> 400 na borda); o controller carrega+autoriza a
  sessao e adiciona o item (get-or-create do container 1:1 + insere, atomico); adapters/out projeta o recibo
  {:id :ordem}. nil (sessao inexistente) -> 404; sessao ja fechada (`:conflito/sessao-fechada`) -> 409 (ledger
  Fase 8 achado #4: sessao ENCERRADA aceitava item de pauta novo); sucesso -> 201 (cria)."
  [repo-sessoes]
  (fn [req]
    (let [ator (:ator req)
          m    (adapters-in-pauta/adicionar-item->dominio (get-in req [:path-params :id]) (:json-params req))]
      (try
        (if-let [recibo (controllers/adicionar-item-pauta repo-sessoes ator m)]
          (http/json-resposta 201 (adapters-out-pauta/recibo-item-adicionado->wire recibo))
          (http/json-resposta 404 {:erro "sessao nao encontrada"}))
        (catch clojure.lang.ExceptionInfo e
          (if (= :conflito/sessao-fechada (:tipo (ex-data e)))
            (resposta-conflito-sessao-fechada e)
            (throw e)))))))

(defn- reordenar-item-handler
  "PATCH /sessoes/:id/pauta/itens/:item-id (§22.6 eixo B). adapters/in coage os path-params + valida o corpo
  {nova-ordem, lock-version}; o controller carrega+autoriza a sessao, checa que o item e' desta sessao (anti
  confused-deputy -> 404) e reordena (CAS); adapters/out projeta o recibo {:id :de :para}. nil (sessao/pauta/item
  ausente) -> 404; lock-stale / item removido -> 409 (nao 500). 200 (atualiza, nao cria)."
  [repo-sessoes]
  (fn [req]
    (let [ator (:ator req)
          m    (adapters-in-pauta/reordenar-item->dominio (get-in req [:path-params :id])
                                                          (get-in req [:path-params :item-id])
                                                          (:json-params req))]
      (try
        (if-let [recibo (controllers/reordenar-item-pauta repo-sessoes ator m)]
          (http/json-resposta 200 (adapters-out-pauta/recibo-reordenacao->wire recibo))
          (http/json-resposta 404 {:erro "sessao ou item de pauta nao encontrado"}))
        (catch clojure.lang.ExceptionInfo e
          (case (:tipo (ex-data e))
            :conflito/pauta
            (http/json-resposta 409 {:erro "item removido ou lock-version desatualizado"})
            :conflito/sessao-fechada (resposta-conflito-sessao-fechada e)
            (throw e)))))))

(defn- anunciar-item-handler
  "POST /sessoes/:id/pauta/itens/:item-id/anuncio (docs/23 Fatia 4b, papel 'secretario'). Sem corpo — o
  instante e' o relogio do servidor e o autor e' o ator. adapters/in coage os path-params; o controller
  carrega+autoriza a sessao, checa que o item e' desta sessao e registra o anuncio (+ evento SSE, mesma tx).
  201 quando o anuncio foi CRIADO; 200 quando o item ja' era o anunciado (reenvio, `:ja-anunciado`) — o corpo
  e' o anuncio existente, no MESMO shape. nil (sessao/pauta/item ausente) -> 404. 409 quando a sessao nao
  esta aberta, ja' fechou, ou o item foi retirado da pauta."
  [repo-sessoes relogio]
  (fn [req]
    (let [ator (:ator req)
          m    (adapters-in-pauta/anunciar-item->dominio (get-in req [:path-params :id])
                                                         (get-in req [:path-params :item-id]))]
      (try
        (if-let [recibo (controllers/anunciar-item-pauta repo-sessoes ator m (tempo/agora relogio))]
          (http/json-resposta (if (:ja-anunciado recibo) 200 201)
                              (adapters-out-pauta/recibo-anuncio->wire recibo))
          (http/json-resposta 404 {:erro "sessao ou item de pauta nao encontrado"}))
        (catch clojure.lang.ExceptionInfo e
          (case (:tipo (ex-data e))
            :conflito/anuncio (http/json-resposta 409 {:erro (ex-message e)})
            :conflito/sessao-fechada (resposta-conflito-sessao-fechada e)
            (throw e)))))))

(defn- remover-item-handler
  "DELETE /sessoes/:id/pauta/itens/:item-id (§22.6 eixo B). adapters/in coage os path-params + valida o corpo
  {tipo, justificativa?, lock-version}; o controller carrega+autoriza a sessao, checa que o item e' desta sessao
  (anti confused-deputy -> 404) e remove SOFT (ativo=false, nunca DELETE fisico — Inv.10; CAS + LOG); adapters/out
  projeta o recibo {:id}. nil -> 404; lock-stale / ja removido -> 409 (nao 500). 200 (atualiza). O corpo (CAS +
  classificacao da remocao) chega pelo corpo-json mesmo no DELETE."
  [repo-sessoes]
  (fn [req]
    (let [ator (:ator req)
          m    (adapters-in-pauta/remover-item->dominio (get-in req [:path-params :id])
                                                        (get-in req [:path-params :item-id])
                                                        (:json-params req))]
      (try
        (if-let [recibo (controllers/remover-item-pauta repo-sessoes ator m)]
          (http/json-resposta 200 (adapters-out-pauta/recibo-remocao->wire recibo))
          (http/json-resposta 404 {:erro "sessao ou item de pauta nao encontrado"}))
        (catch clojure.lang.ExceptionInfo e
          (case (:tipo (ex-data e))
            :conflito/pauta
            (http/json-resposta 409 {:erro "item ja removido ou lock-version desatualizado"})
            :conflito/sessao-fechada (resposta-conflito-sessao-fechada e)
            (throw e)))))))

(defn- pauta-handler
  "GET /sessoes/:id/pauta. adapters/in coage o :id; controller carrega+autoriza a sessao e le a pauta viva;
  adapters/out projeta. nil (sessao inexistente) -> 404. Modo TV (docs/22): depois da authz, enriquece os
  itens de proposicao com o resumo da materia (`resumir-proposicoes`, seam do host) — so' depois, nunca antes:
  quem nao pode ver a sessao nao dispara leitura nenhuma em legislativo."
  [repo-sessoes resumir-proposicoes]
  (fn [req]
    (let [ator (:ator req)
          id   (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-let [p (controllers/pauta-da-sessao repo-sessoes ator id)]
        (http/json-resposta 200 (adapters-out-pauta/pauta->wire
                                  p (controllers/resumos-da-pauta resumir-proposicoes (:ente-id ator) p)))
        (http/json-resposta 404 {:erro "sessao nao encontrada"})))))

(defn- agendar-handler
  "POST /sessoes. corpo JSON parseado em (:json-params req) pelo corpo-json; adapters/in valida+coage+injeta o
  ente/autor do ator; controller agenda; adapters/out projeta o recibo. Sucesso -> 201."
  [repo-sessoes]
  (fn [req]
    (let [ator (:ator req)
          m    (adapters-in/agendar-sessao->dominio ator (:json-params req))]
      (http/json-resposta 201 (adapters-out/recibo-agendamento->wire
                               (controllers/agendar-sessao repo-sessoes ator m))))))

(defn- ingestao-handler
  "POST /gravacoes. Ingestao AGNOSTICA de sessao (§22.3.4 / Opcao A: o segmento chega do CLI/watch-folder SEM
  vinculo; URL de topo, fora de /sessoes/:id). O CORPO e' o binario (container bruto) — NAO passa pelo
  corpo-json; a metadata vem da QUERY (:query-params, keyword-keyed). adapters/in valida+coage a metadata; o
  controller transmite o stream ao objeto_store, computa o hash e registra+emite. Sucesso -> 201; sessao-id
  (link-at-ingest) informado mas inexistente -> 404."
  [repo-sessoes objeto-store]
  (fn [req]
    (let [ator (:ator req)
          metadata (adapters-in-grav/ingestao-meta->dominio (:query-params req))]
      ;; corpo ausente (proxy consumiu / sem Content-Length) = requisicao invalida (-> 400), nunca NPE -> 500
      (when-not (:body req)
        (throw (ex-info "corpo de ingestao ausente" {:tipo :validacao/invalido})))
      (try
        (if-let [recibo (controllers/ingerir-segmento repo-sessoes objeto-store ator metadata
                                                       (limitar-stream (:body req) max-upload-bytes))]
          (http/json-resposta 201 (adapters-out-grav/recibo-ingestao->wire recibo))
          (http/json-resposta 404 {:erro "sessao nao encontrada"}))
        (catch clojure.lang.ExceptionInfo e
          (if (= :corpo/grande (:tipo (ex-data e)))
            (http/json-resposta 413 {:erro "upload grande demais"})
            (throw e)))))))

(defn- vincular-gravacao-handler
  "POST /sessoes/:id/gravacao/:seg-id/vincular (Opcao A pos-upload). adapters/in coage os path-params (sessao :id
  + segmento :seg-id) e valida o corpo {lock-version}; o controller carrega+autoriza a sessao e vincula (uma-vez,
  CAS), re-derivando o sigilo p/ sessao secreta; adapters/out projeta o recibo. nil (sessao inexistente) -> 404;
  conflito de CAS / ja-vinculado / lock-stale -> 409 (nao 500). Usa corpo-json (corpo pequeno, ao contrario do
  upload binario da ingestao)."
  [repo-sessoes]
  (fn [req]
    (let [ator (:ator req)
          m    (adapters-in-grav/vincular->dominio (get-in req [:path-params :id])
                                                   (get-in req [:path-params :seg-id])
                                                   (:json-params req))]
      (try
        (if-let [recibo (controllers/vincular-gravacao repo-sessoes ator m)]
          ;; 200 (nao 201): o vinculo ATUALIZA um segmento ja existente (nao cria recurso) — espelha o
          ;; encerramento de votacao (update=200), distinto da ingestao (cria=201).
          (http/json-resposta 200 (adapters-out-grav/recibo-vinculo->wire recibo))
          (http/json-resposta 404 {:erro "sessao nao encontrada"}))
        (catch clojure.lang.ExceptionInfo e
          (case (:tipo (ex-data e))
            :conflito/vinculo
            (http/json-resposta 409 {:erro "segmento ja vinculado ou lock-version desatualizado"})
            :conflito/sessao-fechada (resposta-conflito-sessao-fechada e)
            (throw e)))))))

(defn- chamada-handler
  "GET /sessoes/:id/chamada (§22.6 eixo C). adapters/in coage o :id; o controller carrega+autoriza a sessao,
  resolve a data de referencia (roster) + o instante de avaliacao (presenca), cruza roster x presenca
  corrente x justificativas (`roster-da-casa`, seam injetado do host sobre cadastros) e deriva estado+quorum
  por vereador; adapters/out projeta. nil (sessao inexistente) -> 404. Sessao agendada SEM data marcada
  (`agendada-para` e' opcional na API e nullable na coluna) -> 409 ACIONAVEL, nunca o 500 'erro interno' do
  interceptor global: quem agendou sem marcar a data precisa saber que e' isso que falta."
  [repo-sessoes roster-da-casa relogio]
  (fn [req]
    (let [ator (:ator req)
          id   (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (try
        (if-let [chamada (controllers/chamada-da-sessao repo-sessoes roster-da-casa ator id relogio)]
          (http/json-resposta 200 (adapters-out-presenca/chamada->wire chamada))
          (http/json-resposta 404 {:erro "sessao nao encontrada"}))
        (catch clojure.lang.ExceptionInfo e
          (if (= :conflito/sessao-sem-data (:tipo (ex-data e)))
            (http/json-resposta 409 {:erro "sessao sem data marcada: informe a data da sessao antes de fazer a chamada"})
            (throw e)))))))

(defn- quorum-handler
  "GET /sessoes/:id/quorum (§22.6 eixo C, Etapa 4a). A leitura MAGRA: so' os numeros, SEM papel exigido na
  borda — o mesmo nivel de authz do irmao `GET /sessoes/:id` (auth + `pode-ver-sessao?` na camada fina).

  O motivo esta na assimetria que esta fatia veio corrigir: o painel do plenario (o telao) abre pelo SSE
  `GET /sessoes/:id/plenario`, que nao exige papel nenhum (`tempo-real/logic/pode-assistir-plenario?` = mesma
  Casa + transmissao publica). Quem ve o telao — Presidente da Mesa, vereador, operador de som — tomava 403
  em `/chamada`, a unica rota que sabia o DENOMINADOR, e o telao exibia 'N presentes' sem 'de M'. Alinhar os
  papeis das duas rotas levaria o `motivo` da justificativa (LGPD, pode ser dado de saude) para esse mesmo
  publico; esta rota nao leva nada nominal, entao nao ha' o que filtrar.

  'Sem papel na borda' NAO e' 'sem politica': a camada FINA roda `logic/pode-ver-quorum-da-sessao?`, que e' a
  politica do telao INTEIRA — mesma Casa E (transmissao publica OU papel 'secretario'). A primeira versao
  desta rota copiou so' a metade que ABRE e deixou cair a que FECHA, e com isso a presenca de uma sessao
  SECRETA (que o SSE recusa por politica explicita) ficou legivel por qualquer vinculo ativo da Casa. 403.

  Mesmos codigos do GET da chamada, porque e' o MESMO controller por dentro: nil -> 404; sessao agendada sem
  data marcada -> 409 acionavel."
  [repo-sessoes roster-da-casa relogio]
  (fn [req]
    (let [ator (:ator req)
          id   (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (try
        (if-let [q (controllers/quorum-da-sessao repo-sessoes roster-da-casa ator id relogio)]
          (http/json-resposta 200 (adapters-out-presenca/quorum-sessao->wire q))
          (http/json-resposta 404 {:erro "sessao nao encontrada"}))
        (catch clojure.lang.ExceptionInfo e
          (if (= :conflito/sessao-sem-data (:tipo (ex-data e)))
            (http/json-resposta 409 {:erro "sessao sem data marcada: informe a data da sessao antes de ler o quorum"})
            (throw e)))))))

(defn- composicao-handler
  "GET /sessoes/:id/composicao (tribuna nominal). SEM papel exigido na borda — o MESMO nivel de authz de
  `/quorum` (irmao literal, mesmo racional de nao-sombreamento).

  O painel do plenario (o telao) resolve `orador-id`/inscritos por UUID porque nunca teve onde perguntar o
  nome: o SSE `GET /sessoes/:id/plenario` carrega so' o id no evento, e a unica rota que sabia o nome
  (`GET /sessoes/:id/chamada`) exige 'secretario' e carrega o `motivo` de justificativa junto (LGPD). Esta
  rota fecha essa lacuna sem repetir a assimetria da Etapa 4a: a camada FINA roda
  `logic/pode-ver-quorum-da-sessao?` (mesma Casa E (transmissao publica OU papel 'secretario')) — NUNCA
  `pode-ver-sessao?` cru, que reabriria a porta dos fundos da sessao SECRETA que aquela etapa fechou (ver a
  docstring de `quorum-handler` e de `pode-ver-quorum-da-sessao?`).

  O payload aceita esse nivel de authz aberto porque `nome-parlamentar`/`cargo-mesa` JA sao servidos SEM
  autenticacao nenhuma por `GET /portal/casa/:ente/vereadores/:id` (verificavel campo a campo contra aquela
  rota); o que essa rota publica NAO serve — `partido` — tambem nao entra aqui (ver `ComposicaoMembroOut`).
  O que de fato e' sensivel (`motivo`, estado de presenca) fica de fora por construcao: `controllers/
  composicao-da-sessao` nunca os inclui na projecao.

  Mesmos codigos do GET de quorum, porque e' o MESMO controller por dentro: nil -> 404; sessao agendada sem
  data marcada -> 409 acionavel."
  [repo-sessoes roster-da-casa relogio]
  (fn [req]
    (let [ator (:ator req)
          id   (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (try
        (if-let [c (controllers/composicao-da-sessao repo-sessoes roster-da-casa ator id relogio)]
          (http/json-resposta 200 (adapters-out-presenca/composicao-sessao->wire c))
          (http/json-resposta 404 {:erro "sessao nao encontrada"}))
        (catch clojure.lang.ExceptionInfo e
          (if (= :conflito/sessao-sem-data (:tipo (ex-data e)))
            (http/json-resposta 409 {:erro "sessao sem data marcada: informe a data da sessao antes de ler a composicao"})
            (throw e)))))))

(defn- tribuna-handler
  "GET /sessoes/:id/tribuna (leitura agregada da tribuna — o read-model que faltava ao telao, ledger de
  prontidao #7). SEM papel exigido na borda — MESMO nivel de authz de `/quorum`/`/composicao` (irmaos
  literais, mesmo racional de nao-sombreamento).

  As 5 rotas de ESCRITA da tribuna (inscrever/desistir/falas/cronometro/encerrar) nunca tiveram uma de
  LEITURA: o painel do plenario so' sabia o orador/a fila por SSE (`GET /sessoes/:id/plenario`), e um
  reload, uma reconexao > 5 min (a janela de replay do canal) ou abrir a tela DEPOIS da fala comecar
  deixavam 'Ninguem com a palavra' com alguem efetivamente falando. Esta rota fecha essa lacuna sem
  reabrir a assimetria da Etapa 4a: a camada FINA roda `logic/pode-ver-quorum-da-sessao?` (mesma Casa E
  (transmissao publica OU papel 'secretario')) — NUNCA `pode-ver-sessao?` cru, que reabriria a porta dos
  fundos da sessao SECRETA que aquela etapa fechou (ver a docstring de `quorum-handler` e de
  `pode-ver-quorum-da-sessao?`). Ao contrario de `/composicao`, esta rota nao carrega nome de vereador
  nenhum — so' UUIDs (o mesmo `orador-id` que o SSE ja' publica) — entao o argumento que abre `/quorum`
  vale aqui SEM a ressalva de identidade que `/composicao` precisou discutir.

  O payload e' a UNIAO EXATA do que `sessoes.events.tribuna` ja' transmite (`FalaIniciadaPayload`/
  `FalaCronometroPayload`/`InscricaoRegistradaPayload`, cada um menos os ids redundantes no
  path/aninhamento) — ver `wire/TribunaOut`. O PUBLICO, porem, e' o do SSE MAIS o secretario nas sessoes
  SECRETAS, nao 'o mesmo publico': o canal `/sessoes/:id/plenario` recusa a subscricao inteira (403) de
  QUALQUER UM, secretario inclusive, quando `transmite_publica=false` (`tempo_real/canais.clj`); esta
  rota usa `pode-ver-quorum-da-sessao?`, que abre excecao para o papel 'secretario' (o mesmo precedente
  de `/quorum`/`/composicao` — e' quem escreve esses mesmos dados). Para `transmite_publica=true` os dois
  publicos SAO identicos; a diferenca so aparece na secreta.

  nil -> 404 (sessao inexistente). SEM o 409 de 'sessao sem data marcada' que `/quorum`/`/composicao`
  tem: aqueles resolvem `instante`/`data-de-composicao` a partir do roster (que precisa de uma data), e
  esta leitura nao cruza roster nenhum — nao ha' o caminho de sessao agendada sem data a mapear."
  [repo-sessoes]
  (fn [req]
    (let [ator (:ator req)
          id   (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-let [t (controllers/tribuna-da-sessao repo-sessoes ator id)]
        (http/json-resposta 200 (adapters-out-tribuna/tribuna-sessao->wire t))
        (http/json-resposta 404 {:erro "sessao nao encontrada"})))))

(defn- conduzir-chamada-handler
  "POST /sessoes/:id/chamada (§22.6 eixo C, Etapa 2d, papel 'secretario'). Sem corpo — `conduzida-por` = o
  ator (anti-forja, mesmo contrato de `confirmar-presenca-handler`), `membros-da-casa` congelado DENTRO da
  tx do Repo (sobre a mesma uniao que `GET .../chamada` publica), `ocorrido-em` = o relogio do servidor
  (nunca do cliente). adapters/in nao entra (nao ha' corpo a coagir, so' o `:id` do path).

  201 quando o ato foi CRIADO; 200 quando foi um REENVIO deduplicado (`:ja-registrado`, duplo clique dentro
  de `logic/janela-de-deduplicacao-de-chamada`) — o corpo e' o ato existente, no MESMO shape. A distincao
  importa: a rota nao tem corpo, entao sem ela o cliente nao consegue diferenciar 'registrei agora' de 'ja
  estava registrado', e a alternativa era gravar um segundo ato num append-only que nao pode ser desfeito.
  nil (sessao inexistente) -> 404. 409 (`:conflito/chamada`) quando o Repo recusa: sessao ja fechou, Casa
  sem nenhum mandato vigente na data (denominador zero), ou teto de atos por sessao."
  [repo-sessoes roster-da-casa relogio]
  (fn [req]
    (let [ator (:ator req)
          id   (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (try
        (if-let [recibo (controllers/registrar-chamada-conduzida repo-sessoes roster-da-casa ator id
                                                                  (tempo/agora relogio))]
          (http/json-resposta (if (:ja-registrado recibo) 200 201)
                              (adapters-out-presenca/chamada-conduzida->wire recibo))
          (http/json-resposta 404 {:erro "sessao nao encontrada"}))
        (catch clojure.lang.ExceptionInfo e
          (case (:tipo (ex-data e))
            :conflito/chamada (http/json-resposta 409 {:erro (ex-message e)})
            ;; `data-de-referencia` roda no controller para resolver o roster: sessao sem data nenhuma cai
            ;; aqui, e e' o MESMO 409 acionavel que o GET da chamada ja devolvia.
            :conflito/sessao-sem-data
            (http/json-resposta 409 {:erro "sessao sem data marcada: informe a data da sessao antes de conduzir a chamada"})
            (throw e)))))))

(defn- listar-gravacoes-handler
  "GET /sessoes/:id/gravacao. adapters/in coage o :id; controller carrega+autoriza a sessao e lista os
  segmentos vinculados; adapters/out projeta (filtra internos). nil (sessao inexistente) -> 404."
  [repo-sessoes]
  (fn [req]
    (let [ator (:ator req)
          id   (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-let [r (controllers/listar-gravacoes repo-sessoes ator id)]
        (http/json-resposta 200 (adapters-out-grav/segmentos->wire (:sessao-id r) (:segmentos r)))
        (http/json-resposta 404 {:erro "sessao nao encontrada"})))))

;; ---------- Etapa 5 fatia 5 — a FOLHA DA SESSAO (rotas) ----------
;; As QUATRO rotas exigem 'secretario' na BORDA e `logic/pode-ver-sessao?` na camada FINA (dentro dos
;; controllers acima) — NUNCA o gate magro do quorum: a folha carrega o `motivo` de justificativa (LGPD,
;; potencial dado de saude), entao herda o gate da chamada NOMINAL, nao o do telao. Vale para as DUAS
;; rotas de leitura tambem — nao ha' um nivel de authz "so' metadados"/"so' conteudo" mais fraco que o do
;; congelamento em si.

(defn- resposta-conflito-folha
  "Traduz os `:tipo` de `ExceptionInfo` que `gerar-folha!` pode lancar. Qualquer outro `:tipo` re-lanca (500
  opaco, fail-closed) — mesmo contrato dos demais `resposta-conflito-*` deste ns."
  [e]
  (case (:tipo (ex-data e))
    :conflito/folha-sessao-aberta
    (http/json-resposta 409 {:erro "so' sessao FECHADA tem folha de presenca (D6)"})
    :validacao/documento-grande
    (http/json-resposta 413 {:erro "documento da folha excede o teto de tamanho"})
    ;; pool DEDICADO de renderizacao saturado (`renderizador-pdf/paralelismo-de-renderizacao`): nao e' erro
    ;; do pedido nem do documento — e' pressao momentanea. 503 + Retry-After diz ao cliente para repetir, e
    ;; a repeticao dentro de 30s cai no dedup de D9 sem criar versao nova.
    :servidor/renderizador-saturado
    (update (http/json-resposta 503 {:erro "renderizador de PDF ocupado — tente novamente em instantes"})
            :headers assoc "Retry-After" "5")
    (throw e)))

(defn- gerar-folha-handler
  "POST /sessoes/:id/folha (Etapa 5 fatia 5, papel 'secretario'). Sem corpo. O controller (`gerar-folha!`)
  carrega+autoriza a sessao (MESMO gate de `folha-da-sessao`), valida o documento contra o Malli ANTES de
  renderizar (D8), decide a versao ANTES de renderizar (D7, para imprimi-la no proprio papel) e congela com
  dedup de 30s (D9). Os TRES ports do mapa `m` (`serializador-folha`/`renderizador-pdf`/`objeto-store`)
  chegam PRONTOS do HOST — construidos UMA vez fora deste handler: `serializador-folha` ja' decorado com o
  TETO de tamanho de ENTRADA, `renderizador-pdf` ja' decorado com TIMEOUT + TETO de SAIDA + POOL DEDICADO
  (as obrigacoes que a revisao de seguranca da Fatia 3 deixou pendentes ate' existir superficie HTTP — esta
  rota E' essa superficie — mais as duas que a revisao adversarial da Fatia 5 acrescentou). Construi-los
  aqui dentro recriaria as duas instancias A CADA REQUEST, sem ganho nenhum.

  nil (sessao inexistente neste ente, cross-tenant inclusive — nao distingue, nao confirma existencia) ->
  404. `:conflito/folha-sessao-aberta` (D6) -> 409. `:validacao/documento-grande` (o TETO reprovando) -> 413.
  `PSQLException` sqlstate 23505 numa SEGUNDA colisao (o RETRY UNICO ja' esta' DENTRO do controller —
  `congelar!`; so' a segunda colisao chega aqui crua, carry escrito na docstring dela) -> 409 traduzido,
  NUNCA o 500 generico. Sucesso -> 201 com os metadados (NUNCA o binario)."
  [repo-sessoes roster-da-casa dados-da-casa relogio serializador-folha renderizador-pdf objeto-store]
  (fn [req]
    (let [ator (:ator req)
          id   (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (try
        (if-let [row (controllers/gerar-folha! repo-sessoes roster-da-casa dados-da-casa ator id relogio
                                               {:serializador serializador-folha
                                                :renderizador-pdf renderizador-pdf
                                                :objeto-store objeto-store})]
          (http/json-resposta 201 (adapters-out-folha/folha->wire row))
          (http/json-resposta 404 {:erro "sessao nao encontrada"}))
        (catch clojure.lang.ExceptionInfo e
          (resposta-conflito-folha e))
        (catch PSQLException e
          (if (= "23505" (.getSQLState e))
            (http/json-resposta 409 {:erro "conflito de versao ao congelar a folha — tente novamente"})
            (throw e)))))))

(defn- listar-folhas-handler
  "GET /sessoes/:id/folhas (Etapa 5 fatia 5). MESMO gate do congelamento. Metadados apenas, SEM binario —
  lista vazia (sessao existe, nunca foi congelada) e' 200, distinto de sessao inexistente (404). docs/23
  Fatia 5: depois da authz, cada versao ganha o nome de quem congelou (`nome-na-casa`, seam do host) — so'
  depois, nunca antes: quem nao pode ver a folha nao dispara leitura nenhuma em identidade."
  [repo-sessoes nome-na-casa]
  (fn [req]
    (let [ator (:ator req)
          id   (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-let [folhas (controllers/folhas-da-sessao-metadados repo-sessoes ator id)]
        (http/json-resposta 200 (adapters-out-folha/folhas-da-sessao->wire
                                  id folhas (controllers/nomes-de-quem-congelou nome-na-casa (:ente-id ator) folhas)))
        (http/json-resposta 404 {:erro "sessao nao encontrada"})))))

(defn- resposta-folha-conteudo
  "Compartilhada por `folha-html-handler`/`folha-pdf-handler`: `nil` (sessao inexistente neste ente) -> 404;
  `:versao-nao-encontrada` -> 404; `:blob-ausente` (ancora-antes-do-blob) -> 500 explicito (NUNCA um render
  novo, NUNCA 404 sobre um congelamento que existe — mesmo contrato de
  `transparencia/baixar-artefato-handler`); `:ok` -> `->resposta` aplicado ao resultado."
  [r ->resposta]
  (cond
    (nil? r) (http/json-resposta 404 {:erro "sessao nao encontrada"})
    (= :ok (:resultado r)) (->resposta r)
    (= :versao-nao-encontrada (:resultado r)) (http/json-resposta 404 {:erro "versao da folha nao encontrada"})
    (= :blob-ausente (:resultado r)) (http/json-resposta 500 {:erro "folha temporariamente indisponivel"})))

(defn- folha-html-handler
  "GET /sessoes/:id/folhas/:versao (Etapa 5 fatia 5). Serve os BYTES CONGELADOS do HTML canonico
  (`text/html; charset=utf-8`) lidos do objeto_store — NUNCA re-renderiza: o que a tela mostra (Fatia 6, num
  `<iframe>`) tem de ser exatamente os bytes cujo hash foi gravado no congelamento. MESMO gate."
  [repo-sessoes objeto-store]
  (fn [req]
    (let [ator   (:ator req)
          id     (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          versao (adapters-in/versao-param->int (get-in req [:path-params :versao]))]
      (resposta-folha-conteudo
       (controllers/folha-conteudo repo-sessoes objeto-store ator id versao :html)
       adapters-out-folha/->html-resposta))))

(defn- folha-pdf-handler
  "GET /sessoes/:id/folhas/:versao/pdf (Etapa 5 fatia 5). Download BINARIO do PDF congelado — MESMO contrato
  de `folha-html-handler`, servindo `application/pdf` + Content-Disposition attachment (o adapter copia a
  forma de `transparencia.adapters.out.artefato/->download`)."
  [repo-sessoes objeto-store]
  (fn [req]
    (let [ator   (:ator req)
          id     (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          versao (adapters-in/versao-param->int (get-in req [:path-params :versao]))]
      (resposta-folha-conteudo
       (controllers/folha-conteudo repo-sessoes objeto-store ator id versao :pdf)
       #(adapters-out-folha/->pdf-download % id)))))

;; ---------- Etapa 6 fatia 3 — a APURACAO DE ASSIDUIDADE (rota) ----------

(defn- assiduidade-handler
  "GET /assiduidade?de=&ate=&tipos=&formato=json|csv&recorte=resumo|detalhe (Etapa 6 fatia 3, papel
  'secretario'). adapters/in coage os query params EM DUAS PARTES: `query->periodo` (`de`/`ate`/`tipos`,
  fail-closed — data malformada NUNCA vira nil silencioso, `:validacao/invalido` -> 400 automatico pelo
  interceptor global `it/erro`) e `query->apresentacao` (`formato`/`recorte`, allowlist estrita — mesma
  regra). O controller (`controllers/apurar-assiduidade`) roda a authz GROSSA de NOVO por dentro (segunda
  camada, docstring dela) e valida `tipos` contra `logic/tipos-sessao` — desconhecido tambem vira 400
  automatico, NUNCA 200 com a apuracao em branco (o achado que 3 revisores da Fatia 2 pegaram
  independentemente). O TETO de periodo/linhas (`:limite/*`) vira 422 com `:medido`/`:teto` pelo MESMO
  interceptor global — este handler NAO repete nenhum desses mapeamentos, so' NAO ENGOLE a excecao antes
  dela chegar la' (SEM try/catch aqui: e' a rota mais simples do modulo por isso).

  `apuracao->wire` roda UMA VEZ (valida contra `AssiduidadeOut`) e serve os DOIS formatos — o CSV reusa o
  MESMO mapa ja' convertido (uuid/LocalDate/keyword -> string), nunca reconverte o modelo cru (a conversao
  mora so' em `adapters.out/apuracao->wire`)."
  [repo-sessoes roster-da-casa-em-datas]
  (fn [req]
    (let [ator (:ator req)
          qp   (:query-params req)
          periodo (adapters-in-assiduidade/query->periodo qp)
          {:keys [formato recorte]} (adapters-in-assiduidade/query->apresentacao qp)
          ;; A BORDA decide se `:detalhe` chega a existir: o JSON sempre o publica, o CSV so' no
          ;; `recorte=detalhe`. `formato=csv&recorte=resumo` era o unico caminho que CONSTRUIA e VALIDAVA por
          ;; Malli ate' 60.000 linhas para nao renderizar nenhuma (achado da revisao adversarial da Fatia 3).
          com-detalhe? (or (= :json formato) (= :detalhe recorte))
          apuracao (controllers/apurar-assiduidade repo-sessoes roster-da-casa-em-datas ator periodo
                                                   {:com-detalhe? com-detalhe?})
          wire (adapters-out-assiduidade/apuracao->wire apuracao com-detalhe?)]
      (case formato
        :json (http/json-resposta 200 wire)
        :csv  (adapters-out-assiduidade/->csv-download
               (adapters-out-assiduidade/apuracao-wire->csv
                wire {:de (:de periodo) :ate (:ate periodo) :tipos (:tipos periodo) :recorte recorte})
               {:de (:de periodo) :ate (:ate periodo) :recorte recorte})))))

(defn rotas
  "Fragmento de rotas do modulo (table syntax Pedestal). Recebe o interceptor `auth` (compartilhado), o
  `repo-sessoes` (Repo-Component) + o `objeto-store` (p/ a ingestao de gravacao e p/ a folha) +
  `resolver-vereador`/`relogio` (Onda C3, borda self-service `/presenca/confirmar` — identidade->vereador-id
  + relogio do servidor, injetados pelo host por inversao de dependencia) + `roster-da-casa`/`dados-da-casa`
  (§22.6 eixo C, borda da CHAMADA/FOLHA — seams injetados do host sobre `cadastros`; este ns nunca importa
  cadastros, §22.10) + `serializador-folha`/`renderizador-pdf` (Etapa 5 fatia 5 — os DOIS ports da folha, JA'
  DECORADOS com os tetos de tamanho, o timeout e o pool dedicado, construidos UMA vez pelo host) +
  `roster-da-casa-em-datas` (Etapa 6 fatia 1 — o LOTE por datas, seam injetado pelo host sobre `cadastros`,
  consumido pela rota de assiduidade abaixo) e devolve as rotas-dado. `oplenario.rotas` funde este fragmento
  ao conjunto. POST exige a authz GROSSA (papel 'secretario', exceto `/presenca/confirmar` que exige
  'vereador'); a ingestao NAO usa corpo-json (o corpo e' binario); GET so autentica (a camada fina decide no
  controller) — EXCETO `/chamada`, `/assiduidade` e as quatro rotas da FOLHA, que exigem 'secretario' na
  borda (leitura operacional da Mesa, nao um read-model publico)."
  [{:keys [auth repo-sessoes objeto-store resolver-vereador relogio roster-da-casa dados-da-casa
           serializador-folha renderizador-pdf roster-da-casa-em-datas resumir-proposicoes nome-na-casa]}]
  ;; ASSERCAO DE BOOT do seam — o carry que as revisoes das Fatias 1 e 2 registraram DUAS vezes e que a
  ;; Fatia 3, que e' quem finalmente destrutura a chave, nao tinha. O mapa que `rotas.clj` passa aqui NAO e'
  ;; `:closed`: uma chave com o nome errado (`:roster-da-casa-em-data`, um typo num refactor) destruturaria
  ;; `nil` em SILENCIO no boot, o processo subiria saudavel, e o defeito so' apareceria na primeira
  ;; requisicao do secretario a `/assiduidade`. Falhar AQUI transforma isso em processo que nao sobe — o
  ;; check equivalente dentro de `controllers/apurar-assiduidade` continua valendo como segunda camada, mas
  ;; ele roda tarde demais para ser um guard-rail de deploy.
  (when-not (ifn? roster-da-casa-em-datas)
    (throw (ex-info "sessoes/rotas: seam :roster-da-casa-em-datas ausente ou nao-funcao"
                    {:tipo :servidor/erro
                     :classe (some-> roster-da-casa-em-datas class .getName)})))
  ;; Modo TV (docs/22): mesmo guard-rail de boot para o seam do resumo da pauta — um typo na chave em
  ;; `rotas.clj` faria a pauta sair sem ementa em silencio (o controller degrada), e ninguem notaria.
  (when-not (ifn? resumir-proposicoes)
    (throw (ex-info "sessoes/rotas: seam :resumir-proposicoes ausente ou nao-funcao"
                    {:tipo :servidor/erro
                     :classe (some-> resumir-proposicoes class .getName)})))
  ;; docs/23 Fatia 5: idem para o nome de quem congelou a folha — sem o guard, um typo na chave faria a lista
  ;; de versoes voltar a mostrar so' o id, em silencio.
  (when-not (ifn? nome-na-casa)
    (throw (ex-info "sessoes/rotas: seam :nome-na-casa ausente ou nao-funcao"
                    {:tipo :servidor/erro
                     :classe (some-> nome-na-casa class .getName)})))
  (let [papel-vereador (it/exige-papel "vereador")]
   #{["/sessoes"     :post [auth (it/exige-papel "secretario") it/corpo-json (agendar-handler repo-sessoes)]
     :route-name :sessoes/agendar]
    ;; MESMO path do POST acima, metodo diferente — Pedestal despacha por (path, metodo); precedente
    ;; identico em `/sessoes/:id/chamada` (GET+POST) e `/pauta/itens/:item-id` (PATCH+DELETE). SEM papel
    ;; exigido: ver a docstring de `listar-handler` — a authz fina roda por LINHA no controller.
    ["/sessoes"     :get  [auth (listar-handler repo-sessoes)] :route-name :sessoes/listar]
    ;; ingestao no TOPO (nao /sessoes/...): o segmento e' agnostico de sessao (Opcao A) e isto evita a colisao
    ;; de roteamento literal-vs-param com /sessoes/:id (o param sombrearia o POST -> 404).
    ["/gravacoes" :post [auth (it/exige-papel "secretario") (ingestao-handler repo-sessoes objeto-store)]
     :route-name :sessoes/ingerir-gravacao]
    ;; Etapa 6 fatia 3 — a APURACAO DE ASSIDUIDADE. TAMBEM no TOPO, pela MESMA razao de `/gravacoes` acima —
    ;; e NAO por precaucao: `/sessoes/assiduidade` foi MEDIDO (repro isolada com `io.pedestal.test/response-
    ;; for` contra um service minimo com so' as duas rotas) e o `:id` de `/sessoes/:id` SOMBREIA o literal
    ;; irmao — `GET /sessoes/assiduidade` roteava para `buscar-handler` (id="assiduidade") e devolvia o corpo
    ;; do `:buscar`, nunca chegando neste handler. E' a mesma limitacao do router prefix-tree do Pedestal 0.7
    ;; documentada em `oplenario.participacao.diplomat.http.in` ("nao admite um literal e um wildcard no
    ;; MESMO nivel de path") — aqui confirmada por execucao, nao so' citada. `/sessoes/:id/chamada` e as
    ;; demais rotas de 3+ segmentos NAO sao afetadas (outro nivel da arvore); o teste de regressao de
    ;; roteamento desta fatia (`assiduidade-rotas-http-in-test`) prova as duas coisas.
    ["/assiduidade" :get [auth (it/exige-papel "secretario") (assiduidade-handler repo-sessoes roster-da-casa-em-datas)]
     :route-name :sessoes/assiduidade]
    ["/sessoes/:id" :get  [auth (buscar-handler repo-sessoes)] :route-name :sessoes/buscar]
    ["/sessoes/:id/transicao" :post
     [auth (it/exige-papel "secretario") it/corpo-json (transicionar-handler repo-sessoes)]
     :route-name :sessoes/transicionar]
    ["/sessoes/:id/presenca" :post
     [auth (it/exige-papel "secretario") it/corpo-json (registrar-presenca-handler repo-sessoes roster-da-casa relogio)]
     :route-name :sessoes/registrar-presenca]
    ["/sessoes/:id/presenca/confirmar" :post
     [auth papel-vereador (confirmar-presenca-handler repo-sessoes roster-da-casa resolver-vereador relogio)]
     :route-name :sessoes/confirmar-minha-presenca]
    ;; `/lote` e' outro literal-sibling de `presenca` (junto de `confirmar`) — sem filho `:param` sob
    ;; `/presenca`, nao ha' o risco de sombreamento literal-vs-param ja documentado em `/gravacoes` e em
    ;; `/minha-justificativa` (prefix-tree do Pedestal so' sombreia quando um `:param` irmao existe).
    ["/sessoes/:id/presenca/lote" :post
     [auth (it/exige-papel "secretario") it/corpo-json (registrar-presenca-lote-handler repo-sessoes roster-da-casa relogio)]
     :route-name :sessoes/registrar-presenca-lote]
    ["/sessoes/:id/chamada" :get
     [auth (it/exige-papel "secretario") (chamada-handler repo-sessoes roster-da-casa relogio)]
     :route-name :sessoes/chamada]
    ;; MESMO path do GET acima, metodo diferente — Pedestal despacha por (path, metodo); nao ha' o risco de
    ;; sombreamento literal-vs-param ja' documentado em `/gravacoes`/`/minha-justificativa` (nao existe filho
    ;; `:param` sob `/chamada`). Precedente de dois metodos no MESMO path: `/pauta/itens/:item-id` (PATCH+DELETE).
    ["/sessoes/:id/chamada" :post
     [auth (it/exige-papel "secretario") (conduzir-chamada-handler repo-sessoes roster-da-casa relogio)]
     :route-name :sessoes/conduzir-chamada]
    ;; Etapa 4a — a leitura MAGRA de quorum. Path IRMAO de `/chamada`, nao um filho dela: os dois sao
    ;; literais sob `/sessoes/:id` (sem filho `:param`, sem risco de sombreamento). SEM `exige-papel` de
    ;; proposito — e' a unica rota de presenca aberta a quem nao e' secretario, e so' pode se-lo porque nao
    ;; carrega nome nem `motivo`. Ver a docstring de `quorum-handler`.
    ["/sessoes/:id/quorum" :get
     [auth (quorum-handler repo-sessoes roster-da-casa relogio)]
     :route-name :sessoes/quorum]
    ;; Tribuna nominal — a COMPOSICAO da sessao. Path IRMAO de `/chamada`/`/quorum`, mesmo racional de
    ;; nao-sombreamento (literal sob `/sessoes/:id`, sem filho `:param`). SEM `exige-papel` de proposito —
    ;; mesmo nivel de authz de `/quorum` (ver a docstring de `composicao-handler`): esta rota carrega NOME,
    ;; mas so' o que a rota publica de vereador ja' serve sem autenticacao.
    ["/sessoes/:id/composicao" :get
     [auth (composicao-handler repo-sessoes roster-da-casa relogio)]
     :route-name :sessoes/composicao]
    ;; A leitura agregada da tribuna — o read-model que faltava ao telao (ledger de prontidao #7). Path
    ;; IRMAO de `/quorum`/`/composicao`, mesmo racional de nao-sombreamento (literal sob `/sessoes/:id`,
    ;; sem filho `:param`). SEM `exige-papel` de proposito — mesmo nivel de authz das duas, e esta nem
    ;; carrega nome (so' UUIDs, o mesmo dado que o SSE ja' publica). Ver a docstring de `tribuna-handler`.
    ["/sessoes/:id/tribuna" :get
     [auth (tribuna-handler repo-sessoes)]
     :route-name :sessoes/tribuna]
    ;; A justificativa tem DUAS portas de abertura, e nao uma que aceite os dois papeis: e' o mesmo desenho
    ;; ja' provado em presenca (`/presenca` da Mesa vs `/presenca/confirmar` do vereador). Numa rota unica o
    ;; significado de `vereador-id` no corpo passaria a depender do PAPEL do ator, e quem tivesse os dois
    ;; papeis justificaria a falta de um terceiro pela porta self-service. Duas rotas tornam a regra
    ;; ESTRUTURAL: na porta self-service o campo nao existe no allowlist do adapters/in.
    ;;
    ;; POR QUE `/minha-justificativa` NO NIVEL DA SESSAO, e nao `/justificativas/minha` (que seria o gemeo
    ;; literal de `/presenca/confirmar`): o prefix-tree do Pedestal NAO resolve literal-vs-param IRMAOS —
    ;; com `/justificativas/:jid/decisao` no mesmo nivel, `/justificativas/minha` fica INALCANCAVEL (404),
    ;; provado por repro minima (o param sombreia o literal). E' a MESMA armadilha ja' anotada acima em
    ;; `/gravacoes`, que so' esta no topo por causa dela. `/presenca/confirmar` escapa porque `presenca` nao
    ;; tem filho param. Aqui `justificativas` tem (`:jid`), entao a porta self-service sobe um nivel.
    ["/sessoes/:id/justificativas" :post
     [auth (it/exige-papel "secretario") it/corpo-json (abrir-justificativa-handler repo-sessoes roster-da-casa)]
     :route-name :sessoes/abrir-justificativa]
    ["/sessoes/:id/minha-justificativa" :post
     [auth papel-vereador it/corpo-json
      (abrir-minha-justificativa-handler repo-sessoes roster-da-casa resolver-vereador)]
     :route-name :sessoes/abrir-minha-justificativa]
    ["/sessoes/:id/justificativas" :get
     [auth (it/exige-papel "secretario") (listar-justificativas-handler repo-sessoes)]
     :route-name :sessoes/listar-justificativas]
    ["/sessoes/:id/justificativas/:jid/decisao" :patch
     [auth (it/exige-papel "secretario") it/corpo-json
      (decidir-justificativa-handler repo-sessoes resolver-vereador)]
     :route-name :sessoes/decidir-justificativa]
    ["/sessoes/:id/inscricoes" :post
     [auth (it/exige-papel "secretario") it/corpo-json (inscrever-handler repo-sessoes)]
     :route-name :sessoes/inscrever-orador]
    ["/sessoes/:id/inscricoes/:insc-id/desistir" :post
     [auth (it/exige-papel "secretario") it/corpo-json (desistir-handler repo-sessoes)]
     :route-name :sessoes/desistir-inscricao]
    ["/sessoes/:id/falas" :post
     [auth (it/exige-papel "secretario") it/corpo-json (iniciar-fala-handler repo-sessoes)]
     :route-name :sessoes/iniciar-fala]
    ["/sessoes/:id/falas/:fala-id/cronometro" :post
     [auth (it/exige-papel "secretario") it/corpo-json (cronometro-handler repo-sessoes)]
     :route-name :sessoes/registrar-evento-cronometro]
    ["/sessoes/:id/falas/:fala-id/encerrar" :post
     [auth (it/exige-papel "secretario") it/corpo-json (encerrar-fala-handler repo-sessoes)]
     :route-name :sessoes/encerrar-fala]
    ["/sessoes/:id/decisoes-mesa" :post
     [auth (it/exige-papel "secretario") it/corpo-json (decisao-mesa-handler repo-sessoes roster-da-casa)]
     :route-name :sessoes/registrar-decisao-mesa]
    ;; docs/23 Fatia 2 — a LEITURA dos atos da Mesa (decisoes + incidentes), para o cockpit.
    ["/sessoes/:id/atos-mesa" :get
     [auth (it/exige-papel "secretario") (atos-mesa-handler repo-sessoes)]
     :route-name :sessoes/atos-mesa]
    ["/sessoes/:id/incidentes" :post
     [auth (it/exige-papel "secretario") it/corpo-json (incidente-handler repo-sessoes)]
     :route-name :sessoes/registrar-incidente]
    ["/sessoes/:id/pauta" :get [auth (pauta-handler repo-sessoes resumir-proposicoes)] :route-name :sessoes/pauta]
    ["/sessoes/:id/pauta/itens" :post
     [auth (it/exige-papel "secretario") it/corpo-json (adicionar-item-handler repo-sessoes)]
     :route-name :sessoes/adicionar-item-pauta]
    ["/sessoes/:id/pauta/itens/:item-id" :patch
     [auth (it/exige-papel "secretario") it/corpo-json (reordenar-item-handler repo-sessoes)]
     :route-name :sessoes/reordenar-item-pauta]
    ["/sessoes/:id/pauta/itens/:item-id" :delete
     [auth (it/exige-papel "secretario") it/corpo-json (remover-item-handler repo-sessoes)]
     :route-name :sessoes/remover-item-pauta]
    ;; docs/23 Fatia 4b — anunciar o item em apreciacao (sem corpo, mesmo molde de POST `/chamada`). Filho de
    ;; `/pauta/itens/:item-id` por um segmento LITERAL a mais: sem ambiguidade com o PATCH/DELETE do item.
    ["/sessoes/:id/pauta/itens/:item-id/anuncio" :post
     [auth (it/exige-papel "secretario") (anunciar-item-handler repo-sessoes relogio)]
     :route-name :sessoes/anunciar-item-pauta]
    ["/sessoes/:id/gravacao" :get [auth (listar-gravacoes-handler repo-sessoes)] :route-name :sessoes/listar-gravacoes]
    ["/sessoes/:id/gravacao/:seg-id/vincular" :post
     [auth (it/exige-papel "secretario") it/corpo-json (vincular-gravacao-handler repo-sessoes)]
     :route-name :sessoes/vincular-gravacao]
    ;; Etapa 5 fatia 5 — a FOLHA DA SESSAO. `/folha` (singular, POST) e `/folhas` (plural, GET) sao literais
    ;; IRMAOS sob `/sessoes/:id` (sem risco de sombreamento literal-vs-param, mesma analise ja' documentada
    ;; em `/gravacoes`/`/minha-justificativa`); `/folhas/:versao` e `/folhas/:versao/pdf` sao os unicos
    ;; filhos de `/folhas`, entao tambem sem ambiguidade. As QUATRO exigem 'secretario' na borda.
    ["/sessoes/:id/folha" :post
     [auth (it/exige-papel "secretario")
      (gerar-folha-handler repo-sessoes roster-da-casa dados-da-casa relogio
                           serializador-folha renderizador-pdf objeto-store)]
     :route-name :sessoes/gerar-folha]
    ["/sessoes/:id/folhas" :get
     [auth (it/exige-papel "secretario") (listar-folhas-handler repo-sessoes nome-na-casa)]
     :route-name :sessoes/listar-folhas]
    ["/sessoes/:id/folhas/:versao" :get
     [auth (it/exige-papel "secretario") (folha-html-handler repo-sessoes objeto-store)]
     :route-name :sessoes/folha-html]
    ["/sessoes/:id/folhas/:versao/pdf" :get
     [auth (it/exige-papel "secretario") (folha-pdf-handler repo-sessoes objeto-store)]
     :route-name :sessoes/folha-pdf]}))

(defn presenca-resumo-wire
  "Ponto de entrada IN-PROCESS da presenca agregada (FE Onda A1) — o gemeo nao-HTTP p/ a RAIZ DE COMPOSICAO
  (o host) compor o dashboard da Mesa do modulo `paineis`. Passa pelo MESMO gate adapters/out (projeta+valida)
  que uma rota HTTP teria. `membros-da-casa` chega JA RESOLVIDO pelo host (inversao de dependencia sobre
  `cadastros` — `sessoes` nunca importa `cadastros`, §22.10).

  CONVENCAO DE AUTHZ (mesmo contrato de `oplenario.compliance.diplomat.http.in/painel-wire`): esta fn NAO
  re-verifica papel/permissao; o ENDPOINT COMPONHEDOR e' o unico ponto de enforcement (GET /paineis/mesa,
  wired numa task posterior, ja' exige papel 'secretario'). QUALQUER novo caller DEVE aplicar o gate
  'secretario' antes — senao expoe a presenca agregada tenant-wide a um papel qualquer. Nao ha lint que
  force isso: e' convencao, mantida por revisao."
  [repo-sessoes membros-da-casa ente-id]
  (adapters-out-presenca/resumo-presenca->wire
   (controllers/resumo-presenca repo-sessoes ente-id (membros-da-casa ente-id))))
