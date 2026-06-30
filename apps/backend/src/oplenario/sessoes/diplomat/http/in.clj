(ns oplenario.sessoes.diplomat.http.in
  "Fronteira de IO HTTP de ENTRADA do modulo sessoes (§22.10 diplomat/http/in, ADR-0001): as rotas-dado Pedestal
  + os handlers. O diplomat e' a UNICA camada que atravessa o gate de borda: chama adapters/in (wire->models) na
  entrada e adapters/out (models->wire) na saida; o controller trabalha so em models. Le o `ator` (posto pela
  cadeia de auth em (:request :ator)), nunca fala com db/ direto (depende do Repo-Component, injetado por closure
  via `rotas`). A authz GROSSA (exige-papel) entra na rota; a FINA (policy.check) roda no controller."
  (:require [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.sessoes.adapters.in.gravacao :as adapters-in-grav]
            [oplenario.sessoes.adapters.in.pauta :as adapters-in-pauta]
            [oplenario.sessoes.adapters.in.presenca :as adapters-in-presenca]
            [oplenario.sessoes.adapters.in.sessao :as adapters-in]
            [oplenario.sessoes.adapters.in.tribuna :as adapters-in-tribuna]
            [oplenario.sessoes.adapters.out.gravacao :as adapters-out-grav]
            [oplenario.sessoes.adapters.out.pauta :as adapters-out-pauta]
            [oplenario.sessoes.adapters.out.presenca :as adapters-out-presenca]
            [oplenario.sessoes.adapters.out.sessao :as adapters-out]
            [oplenario.sessoes.adapters.out.tribuna :as adapters-out-tribuna]
            [oplenario.sessoes.controllers :as controllers]))

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

(defn- registrar-presenca-handler
  "POST /sessoes/:id/presenca (§22.6 eixo C). adapters/in coage o :id + valida o corpo {vereador-id, tipo,
  modalidade, ocorrido-em}; o controller carrega+autoriza a sessao e grava o evento append-only (a fonte e'
  forcada = manual_secretaria; o Repo emite presenca.registrada na mesma tx); adapters/out projeta o recibo.
  nil (sessao inexistente) -> 404; sucesso -> 201 (cria um evento — append-only, sem CAS/409)."
  [repo-sessoes]
  (fn [req]
    (let [ator (:ator req)
          m    (adapters-in-presenca/registrar-presenca->dominio (get-in req [:path-params :id]) (:json-params req))]
      (if-let [recibo (controllers/registrar-presenca repo-sessoes ator m)]
        (http/json-resposta 201 (adapters-out-presenca/recibo-presenca->wire recibo))
        (http/json-resposta 404 {:erro "sessao nao encontrada"})))))

(defn- inscrever-handler
  "POST /sessoes/:id/inscricoes (§22.6 eixo F, tribuna). adapters/in coage o :id + valida o corpo {vereador-id,
  origem-inscricao, fase, proposicao-ref-id?}; o controller carrega+autoriza a sessao e inscreve (o Repo emite
  inscricao.registrada na mesma tx); adapters/out projeta o recibo {:id :ordem}. nil -> 404; sucesso -> 201."
  [repo-sessoes]
  (fn [req]
    (let [ator (:ator req)
          m    (adapters-in-tribuna/inscrever->dominio (get-in req [:path-params :id]) (:json-params req))]
      (if-let [recibo (controllers/inscrever-orador repo-sessoes ator m)]
        (http/json-resposta 201 (adapters-out-tribuna/recibo-inscricao->wire recibo))
        (http/json-resposta 404 {:erro "sessao nao encontrada"})))))

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
          (if (= :conflito/inscricao (:tipo (ex-data e)))
            (http/json-resposta 409 {:erro "inscricao ja desistida, inexistente ou lock-version desatualizado"})
            (throw e)))))))

(defn- iniciar-fala-handler
  "POST /sessoes/:id/falas (§22.6 eixo F, tribuna execucao). adapters/in coage o :id + valida o corpo {orador-id,
  tipo-fala, fase, iniciou-em, ...?}; o controller carrega+autoriza a sessao e inicia a fala (o Repo loga
  'iniciada' + emite fala.iniciada na mesma tx); adapters/out projeta o recibo {:fala-id}. nil -> 404;
  sucesso -> 201 (cria)."
  [repo-sessoes]
  (fn [req]
    (let [ator (:ator req)
          m    (adapters-in-tribuna/iniciar-fala->dominio (get-in req [:path-params :id]) (:json-params req))]
      (if-let [recibo (controllers/iniciar-fala repo-sessoes ator m)]
        (http/json-resposta 201 (adapters-out-tribuna/recibo-fala-iniciada->wire recibo))
        (http/json-resposta 404 {:erro "sessao nao encontrada"})))))

(defn- cronometro-handler
  "POST /sessoes/:id/falas/:fala-id/cronometro (§22.6 eixo F). adapters/in coage os path-params + valida o corpo
  {tipo, ocorrido-em, segundos-adicionais?} INCL. a coerencia tipo<->segundos (-> 400 na borda); o controller
  carrega+autoriza a sessao e registra o evento append-only (o Repo emite fala.cronometro na mesma tx);
  adapters/out projeta o recibo {:id}. nil (sessao inexistente) -> 404; sucesso -> 201 (cria evento)."
  [repo-sessoes]
  (fn [req]
    (let [ator (:ator req)
          m    (adapters-in-tribuna/cronometro->dominio (get-in req [:path-params :id])
                                                        (get-in req [:path-params :fala-id])
                                                        (:json-params req))]
      (if-let [recibo (controllers/registrar-evento-cronometro repo-sessoes ator m)]
        (http/json-resposta 201 (adapters-out-tribuna/recibo-cronometro->wire recibo))
        (http/json-resposta 404 {:erro "sessao nao encontrada"})))))

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
          (if (= :conflito/fala (:tipo (ex-data e)))
            (http/json-resposta 409 {:erro "fala ja encerrada, inexistente ou lock-version desatualizado"})
            (throw e)))))))

(defn- decisao-mesa-handler
  "POST /sessoes/:id/decisoes-mesa (§22.6 eixo F, tribuna). adapters/in coage o :id + valida o corpo {questao,
  decisao, decidido-em, fundamentacao?, fala-id?} INCL. o nao-vazio de questao/decisao (-> 400 na borda); o
  controller carrega+autoriza a sessao, injeta presidente-id/created-by do ator e registra a decisao append-only
  (sem CAS, sem evento; se fala-id veio, tem de ser desta sessao -> senao 404); adapters/out projeta o recibo
  {:id}. nil (sessao inexistente / fala alheia) -> 404; sucesso -> 201 (cria o ato — append-only, sem 409)."
  [repo-sessoes]
  (fn [req]
    (let [ator (:ator req)
          m    (adapters-in-tribuna/decisao-mesa->dominio (get-in req [:path-params :id]) (:json-params req))]
      (if-let [recibo (controllers/registrar-decisao-mesa repo-sessoes ator m)]
        (http/json-resposta 201 (adapters-out-tribuna/recibo-decisao-mesa->wire recibo))
        (http/json-resposta 404 {:erro "sessao nao encontrada"})))))

(defn- adicionar-item-handler
  "POST /sessoes/:id/pauta/itens (§22.6 eixo B). adapters/in coage o :id + valida o corpo {fase, tipo-item,
  proposicao-id? | texto-descricao?} INCL. a FK-por-tipo (-> 400 na borda); o controller carrega+autoriza a
  sessao e adiciona o item (get-or-create do container 1:1 + insere, atomico); adapters/out projeta o recibo
  {:id :ordem}. nil (sessao inexistente) -> 404; sucesso -> 201 (cria)."
  [repo-sessoes]
  (fn [req]
    (let [ator (:ator req)
          m    (adapters-in-pauta/adicionar-item->dominio (get-in req [:path-params :id]) (:json-params req))]
      (if-let [recibo (controllers/adicionar-item-pauta repo-sessoes ator m)]
        (http/json-resposta 201 (adapters-out-pauta/recibo-item-adicionado->wire recibo))
        (http/json-resposta 404 {:erro "sessao nao encontrada"})))))

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
          (if (= :conflito/pauta (:tipo (ex-data e)))
            (http/json-resposta 409 {:erro "item removido ou lock-version desatualizado"})
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
          (if (= :conflito/pauta (:tipo (ex-data e)))
            (http/json-resposta 409 {:erro "item ja removido ou lock-version desatualizado"})
            (throw e)))))))

(defn- pauta-handler
  "GET /sessoes/:id/pauta. adapters/in coage o :id; controller carrega+autoriza a sessao e le a pauta viva;
  adapters/out projeta. nil (sessao inexistente) -> 404."
  [repo-sessoes]
  (fn [req]
    (let [ator (:ator req)
          id   (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-let [p (controllers/pauta-da-sessao repo-sessoes ator id)]
        (http/json-resposta 200 (adapters-out-pauta/pauta->wire p))
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
          (if (= :conflito/vinculo (:tipo (ex-data e)))
            (http/json-resposta 409 {:erro "segmento ja vinculado ou lock-version desatualizado"})
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

(defn rotas
  "Fragmento de rotas do modulo (table syntax Pedestal). Recebe o interceptor `auth` (compartilhado), o
  `repo-sessoes` (Repo-Component) + o `objeto-store` (p/ a ingestao de gravacao) e devolve as rotas-dado.
  `oplenario.rotas` funde este fragmento ao conjunto. POST exige a authz GROSSA (papel 'secretario'); a
  ingestao NAO usa corpo-json (o corpo e' binario); GET so autentica (a camada fina decide no controller)."
  [{:keys [auth repo-sessoes objeto-store]}]
  #{["/sessoes"     :post [auth (it/exige-papel "secretario") it/corpo-json (agendar-handler repo-sessoes)]
     :route-name :sessoes/agendar]
    ;; ingestao no TOPO (nao /sessoes/...): o segmento e' agnostico de sessao (Opcao A) e isto evita a colisao
    ;; de roteamento literal-vs-param com /sessoes/:id (o param sombrearia o POST -> 404).
    ["/gravacoes" :post [auth (it/exige-papel "secretario") (ingestao-handler repo-sessoes objeto-store)]
     :route-name :sessoes/ingerir-gravacao]
    ["/sessoes/:id" :get  [auth (buscar-handler repo-sessoes)] :route-name :sessoes/buscar]
    ["/sessoes/:id/transicao" :post
     [auth (it/exige-papel "secretario") it/corpo-json (transicionar-handler repo-sessoes)]
     :route-name :sessoes/transicionar]
    ["/sessoes/:id/presenca" :post
     [auth (it/exige-papel "secretario") it/corpo-json (registrar-presenca-handler repo-sessoes)]
     :route-name :sessoes/registrar-presenca]
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
     [auth (it/exige-papel "secretario") it/corpo-json (decisao-mesa-handler repo-sessoes)]
     :route-name :sessoes/registrar-decisao-mesa]
    ["/sessoes/:id/pauta" :get [auth (pauta-handler repo-sessoes)] :route-name :sessoes/pauta]
    ["/sessoes/:id/pauta/itens" :post
     [auth (it/exige-papel "secretario") it/corpo-json (adicionar-item-handler repo-sessoes)]
     :route-name :sessoes/adicionar-item-pauta]
    ["/sessoes/:id/pauta/itens/:item-id" :patch
     [auth (it/exige-papel "secretario") it/corpo-json (reordenar-item-handler repo-sessoes)]
     :route-name :sessoes/reordenar-item-pauta]
    ["/sessoes/:id/pauta/itens/:item-id" :delete
     [auth (it/exige-papel "secretario") it/corpo-json (remover-item-handler repo-sessoes)]
     :route-name :sessoes/remover-item-pauta]
    ["/sessoes/:id/gravacao" :get [auth (listar-gravacoes-handler repo-sessoes)] :route-name :sessoes/listar-gravacoes]
    ["/sessoes/:id/gravacao/:seg-id/vincular" :post
     [auth (it/exige-papel "secretario") it/corpo-json (vincular-gravacao-handler repo-sessoes)]
     :route-name :sessoes/vincular-gravacao]})
