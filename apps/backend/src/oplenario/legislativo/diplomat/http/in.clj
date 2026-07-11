(ns oplenario.legislativo.diplomat.http.in
  "Fronteira de IO HTTP de ENTRADA do legislativo (§22.10 diplomat/http/in, ADR-0001): TRES verticais moram
  aqui (docstring atualizada — review MENOR fe-9-ficha-materia: a lista estava presa em 'DUAS verticais' e
  ja' nao refletia a silhueta real das rotas). (1) a votacao ao vivo (F4 Slice 3) — abrir / registrar voto /
  encerrar. A rota mora AQUI (legislativo e' o DONO do agregado votacao + da tx que casa ato+emissao, Slice
  1), nao no `sessoes` — espelha o SSE `/sessoes/:id/plenario` que mora no `tempo_real` (prefixo de URL !=
  dono do modulo). (2) CRUD+detalhe de proposicoes (Onda B Slices 1-2) — listar (tenant-wide, paginado),
  criar, detalhe, editar (PATCH parcial + versao 'edicao'). (3) ficha da materia (Onda B Slice 3) — leitura
  agregada cross-eixo (proposicao+texto+tramitacao+apensadas+emendas+pareceres) NUMA rota so'. Todas so' o
  gate grosso (papel 'secretario'), sem authz herdada de sessao. O diplomat e' a UNICA camada que cruza o
  gate de borda (adapters/in na entrada, adapters/out na saida); o controller trabalha so em models. Na
  vertical de votacao a authz e' HERDADA do recurso SESSAO via `consultar-sessao` INJETADA pelo host
  (legislativo NAO importa sessoes, §22.10): a grossa (exige-papel) na rota, a fina
  (policy.check/pode-dirigir-votacao?) no controller."
  (:require [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.legislativo.adapters.in.ciencia :as adapters-in-ciencia]
            [oplenario.legislativo.adapters.in.documento :as adapters-in-documento]
            [oplenario.legislativo.adapters.in.parecer :as adapters-in-parecer]
            [oplenario.legislativo.adapters.in.proposicao :as adapters-in-proposicao]
            [oplenario.legislativo.adapters.in.votacao :as adapters-in]
            [oplenario.legislativo.adapters.out.documento :as adapters-out-documento]
            [oplenario.legislativo.adapters.out.documento-modelo :as adapters-out-documento-modelo]
            [oplenario.legislativo.adapters.out.ficha-materia :as adapters-out-ficha]
            [oplenario.legislativo.adapters.out.meu-painel :as adapters-out-meu-painel]
            [oplenario.legislativo.adapters.in.pos-aprovacao :as adapters-in-pos-aprovacao]
            [oplenario.legislativo.adapters.out.autografo :as adapters-out-autografo]
            [oplenario.legislativo.adapters.out.parecer :as adapters-out-parecer]
            [oplenario.legislativo.adapters.out.pos-aprovacao :as adapters-out-pos-aprovacao]
            [oplenario.legislativo.adapters.out.proposicao :as adapters-out-proposicao]
            [oplenario.legislativo.adapters.out.protocolo-geral :as adapters-out-protocolo]
            [oplenario.legislativo.adapters.out.relator-pendente :as adapters-out-relator]
            [oplenario.legislativo.adapters.out.tramitacao-executiva :as adapters-out-tramitacao-executiva]
            [oplenario.legislativo.adapters.out.votacao :as adapters-out]
            [oplenario.legislativo.controllers :as controllers])
  (:import (java.time ZoneId)))

(set! *warn-on-reflection* true)

;; fuso civil p/ `agora` (LocalDate) do gatilho de emissao do parecer — prazos/regras do motor operam em
;; data civil, nao UTC (review MEDIUM fe-11-parecer); mesma constante de participacao.controllers/zona-civil.
(def ^:private zona-civil (ZoneId/of "America/Fortaleza"))

(defn- abrir-handler
  "POST /sessoes/:id/votacoes. corpo-json -> :json-params; adapters/in valida+coage+injeta id/autor; controller
  autoriza na sessao (:id) e abre; adapters/out projeta o recibo. nil (sessao inexistente) -> 404."
  [repo-leg consultar-sessao]
  (fn [req]
    (let [ator (:ator req)
          sid  (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          m    (adapters-in/abrir-votacao->dominio ator (:json-params req))]
      (if-let [recibo (controllers/abrir-votacao repo-leg consultar-sessao ator sid m)]
        (http/json-resposta 201 (adapters-out/abertura->wire recibo))
        (http/json-resposta 404 {:erro "sessao nao encontrada"})))))

(defn- voto-handler
  "POST /sessoes/:id/votacoes/:votacao-id/votos. Authz na sessao + amarra votacao<->sessao; dispatch por
  modalidade no controller. nil (votacao inexistente ou de outra sessao) -> 404."
  [repo-leg consultar-sessao]
  (fn [req]
    (let [ator (:ator req)
          sid  (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          vid  (adapters-in/id-param->uuid (get-in req [:path-params :votacao-id]))
          m    (adapters-in/registrar-voto->dominio ator vid (:json-params req))]
      (if-let [recibo (controllers/registrar-voto repo-leg consultar-sessao ator sid vid m)]
        (http/json-resposta 201 (adapters-out/voto->wire recibo))
        (http/json-resposta 404 {:erro "votacao nao encontrada nesta sessao"})))))

(defn- meu-voto-handler
  "POST /sessoes/:id/votacoes/:votacao-id/meu-voto (Onda C3, papel 'vereador'). `hoje`/`instante` resolvidos
  AQUI, na borda (mesmo padrao de emitir-parecer-handler/`agora`) — o controller nao le o relogio."
  [repo-leg consultar-sessao resolver-vereador registro relogio]
  (fn [req]
    (let [ator (:ator req)
          sid  (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          vid  (adapters-in/id-param->uuid (get-in req [:path-params :votacao-id]))
          instante (tempo/agora relogio)
          hoje (tempo/hoje-de instante zona-civil)
          m    (adapters-in/meu-voto->dominio ator vid (:json-params req))]
      (if-let [recibo (controllers/meu-voto repo-leg consultar-sessao resolver-vereador registro ator sid vid hoje instante m)]
        (http/json-resposta 201 (adapters-out/voto->wire recibo))
        (http/json-resposta 404 {:erro "vereador sem cadastro vinculado, ou sessao/votacao nao encontrada"})))))

(defn- encerrar-handler
  "POST /sessoes/:id/votacoes/:votacao-id/encerramento. Apura + grava o snapshot (CAS); adapters/out projeta os
  totais. nil (votacao inexistente ou de outra sessao) -> 404."
  [repo-leg consultar-sessao]
  (fn [req]
    (let [ator (:ator req)
          sid  (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          vid  (adapters-in/id-param->uuid (get-in req [:path-params :votacao-id]))
          m    (adapters-in/encerrar-votacao->dominio ator vid (:json-params req))]
      (if-let [snap (controllers/encerrar-votacao repo-leg consultar-sessao ator sid vid m)]
        (http/json-resposta 200 (adapters-out/encerramento->wire snap))
        (http/json-resposta 404 {:erro "votacao nao encontrada nesta sessao"})))))

(defn- listar-proposicoes-handler
  "GET /legislativo/proposicoes(?busca=&tipo=&estado=&autor-id=&ano=&pagina=&tamanho=&ordenar-por=&ordenar-dir=).
  Leitura tenant-wide (mesmo contrato de authz de /paineis/*, Onda B Slice 1): adapters/in coage os filtros
  (fail-closed -> 400); o controller le' do tenant do ator; adapters/out projeta+valida."
  [repo-leg]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))
          filtro (adapters-in-proposicao/listar-proposicoes->dominio (:query-params req))]
      (http/json-resposta 200 (adapters-out-proposicao/listar->wire
                                (controllers/listar-proposicoes repo-leg ente-id filtro))))))

(defn- criar-proposicao-handler
  "POST /legislativo/proposicoes. Cria + relê o detalhe (o Repo devolve so' {:id :sequencial :urn-lex})."
  [repo-leg resolver-municipio]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          m (adapters-in-proposicao/criar-proposicao->dominio ator (:json-params req))]
      (controllers/criar-proposicao repo-leg resolver-municipio ente-id m)
      (let [{:keys [proposicao texto]} (controllers/buscar-proposicao-ficha repo-leg ente-id (:id m))]
        (http/json-resposta 201 (adapters-out-proposicao/detalhe->wire proposicao texto))))))

(defn- detalhe-proposicao-handler
  "GET /legislativo/proposicoes/:id."
  [repo-leg]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))
          id (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-let [{:keys [proposicao texto]} (controllers/buscar-proposicao-ficha repo-leg ente-id id)]
        (http/json-resposta 200 (adapters-out-proposicao/detalhe->wire proposicao texto))
        (http/json-resposta 404 {:erro "proposicao nao encontrada"})))))

(defn- ficha-materia-handler
  "GET /legislativo/proposicoes/:id/ficha (Onda B Slice 3). Mesmo gate grosso das rotas irmas (papel
  'secretario'); nil (proposicao inexistente ou de outro tenant) -> 404, nunca vaza. O diplomat compoe os
  DOIS adapters/out (proposicao p/ o cabecalho + ficha-materia p/ o envelope) — adapters/ nunca chama outro
  adapters/ (ADR-0001 §3). `:texto` ja' chega EXTRAIDO do controller (string/nil — review MENOR
  fe-9-ficha-materia: o diplomat nunca decide nome de campo do model, so' compoe)."
  [repo-leg]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))
          id (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-let [{:keys [proposicao texto] :as ficha} (controllers/buscar-ficha-materia repo-leg ente-id id)]
        (http/json-resposta 200 (adapters-out-ficha/ficha->wire
                                   (adapters-out-proposicao/detalhe->wire proposicao texto)
                                   ficha))
        (http/json-resposta 404 {:erro "proposicao nao encontrada"})))))

(defn- editar-proposicao-handler
  "PATCH /legislativo/proposicoes/:id. PRE-CHECK via `buscar-proposicao-ficha` ANTES de editar — 404 imediato
  se a proposicao nao existir (mesmo contrato de 404 do GET de detalhe — nunca 500). Sem o pre-check,
  `editar-proposicao` alcanca `db/proposicao.clj`'s `editar!`, que lanca `ex-info` SEM `:tipo` assim que acha
  a linha ausente — o interceptor global de erro (`oplenario.interceptors/erro`) so' mapeia `:validacao/
  invalido`->400 e `authz/negado?`->403, entao essa excecao cai no fallback generico -> 500 (bug real, achado
  em review). Apos o pre-check passar, edita + rele' o detalhe p/ o corpo 200."
  [repo-leg]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          id (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-not (controllers/buscar-proposicao-ficha repo-leg ente-id id)
        (http/json-resposta 404 {:erro "proposicao nao encontrada"})
        (let [m (adapters-in-proposicao/editar-proposicao->dominio ator id (:json-params req))]
          (controllers/editar-proposicao repo-leg ente-id m)
          (if-let [{:keys [proposicao texto]} (controllers/buscar-proposicao-ficha repo-leg ente-id id)]
            (http/json-resposta 200 (adapters-out-proposicao/detalhe->wire proposicao texto))
            (http/json-resposta 404 {:erro "proposicao nao encontrada"})))))))

(defn- parecer-editor-handler
  "GET /legislativo/pareceres/:id (Onda B Slice 5). nil (parecer inexistente ou de outro tenant) -> 404."
  [repo-leg]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))
          id (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-let [dados (controllers/buscar-parecer-editor repo-leg ente-id id)]
        (http/json-resposta 200 (adapters-out-parecer/editor->wire dados))
        (http/json-resposta 404 {:erro "parecer nao encontrado"})))))

(defn- salvar-rascunho-parecer-handler
  "PATCH /legislativo/pareceres/:id. PRE-CHECK 404 ANTES de escrever se o parecer nao existir (mesmo
  contrato de editar-proposicao-handler — evita a ex-info sem :tipo do db/ cair no fallback 500)."
  [repo-leg]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          id (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-not (controllers/buscar-parecer-editor repo-leg ente-id id)
        (http/json-resposta 404 {:erro "parecer nao encontrado"})
        (let [m (adapters-in-parecer/salvar-rascunho->dominio ator id (:json-params req))]
          (controllers/salvar-rascunho-parecer repo-leg ente-id m)
          (http/json-resposta 200 (adapters-out-parecer/editor->wire
                                     (controllers/buscar-parecer-editor repo-leg ente-id id))))))))

(defn- emitir-parecer-handler
  "POST /legislativo/pareceres/:id/emissao. O TEMPLATE-ID vem do parecer JA' CARREGADO (o adapters/in nao
  tem outra forma de sabe-lo, review de spec) — mesmo pre-check tambem serve de gate 404. `registro`
  (RegistroFatos do motor) e' injetado pelo host (mesmo componente de transicionar-parecer!). `relogio`
  (kernel/tempo, injetavel em teste — review MEDIUM fe-11-parecer) resolve `agora` AQUI, na borda; o
  adapters/in so' traduz."
  [repo-leg registro relogio]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          id (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          agora (tempo/hoje relogio zona-civil)]
      (if-let [{:keys [parecer]} (controllers/buscar-parecer-editor repo-leg ente-id id)]
        (let [m (adapters-in-parecer/emitir->dominio ator id (:template-id parecer) agora (:json-params req))]
          (controllers/emitir-parecer repo-leg registro ente-id m)
          (http/json-resposta 200 (adapters-out-parecer/editor->wire
                                     (controllers/buscar-parecer-editor repo-leg ente-id id))))
        (http/json-resposta 404 {:erro "parecer nao encontrado"})))))

;; ========================= Onda B Slice 6: expediente (documentos + protocolo geral) =========================

(defn- listar-modelos-documento-handler
  "GET /legislativo/documento-modelos (Onda B Slice 6). Seletor da aba 'Gerar documento' — so' os ATIVOS."
  [repo-leg]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))]
      (http/json-resposta 200 (adapters-out-documento-modelo/modelos->wire
                                 (controllers/listar-modelos-documento repo-leg ente-id))))))

(defn- gerar-documento-handler
  "POST /legislativo/documentos. `m` ja' carrega o `:id` novo (gerado pelo adapters/in) — o controller resolve
  o modelo (404 se inexistente no tenant) e gera; o handler RE-LE pelo mesmo id p/ o corpo 201 completo (mesmo
  padrao de criar-proposicao-handler)."
  [repo-leg]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          m (adapters-in-documento/gerar-documento->dominio ator (:json-params req))]
      (if (controllers/gerar-documento repo-leg ente-id m)
        (let [{:keys [documento protocolo]} (controllers/buscar-documento-editor repo-leg ente-id (:id m))]
          (http/json-resposta 201 (adapters-out-documento/documento->wire documento protocolo)))
        (http/json-resposta 404 {:erro "modelo de documento nao encontrado"})))))

(defn- detalhe-documento-handler
  "GET /legislativo/documentos/:id (Onda B Slice 6). nil (documento inexistente ou de outro tenant) -> 404."
  [repo-leg]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))
          id (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-let [{:keys [documento protocolo]} (controllers/buscar-documento-editor repo-leg ente-id id)]
        (http/json-resposta 200 (adapters-out-documento/documento->wire documento protocolo))
        (http/json-resposta 404 {:erro "documento nao encontrado"})))))

(defn- editar-documento-handler
  "PATCH /legislativo/documentos/:id. PRE-CHECK 404 ANTES de escrever se o documento nao existir (mesmo
  contrato de salvar-rascunho-parecer-handler — evita a ex-info sem :tipo do db/ cair no fallback 500)."
  [repo-leg]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          id (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-not (controllers/buscar-documento-editor repo-leg ente-id id)
        (http/json-resposta 404 {:erro "documento nao encontrado"})
        (let [m (adapters-in-documento/editar-documento->dominio ator id (:json-params req))]
          (controllers/editar-documento repo-leg ente-id m)
          (let [{:keys [documento protocolo]} (controllers/buscar-documento-editor repo-leg ente-id id)]
            (http/json-resposta 200 (adapters-out-documento/documento->wire documento protocolo))))))))

(defn- protocolar-documento-handler
  "POST /legislativo/documentos/:id/protocolo — o CTA 'Protocolar e numerar' do mockup. PRE-CHECK 404 (mesmo
  contrato das rotas irmas). `ano` (kernel/tempo, injetavel em teste) resolve a data civil AQUI, na borda —
  mesmo padrao de emitir-parecer-handler/`agora`. Documento ja' 'emitido' (re-protocolar) ou lock-version
  desatualizado -> `:validacao/invalido` no db/documento.clj (Repo/protocolar-documento!) -> 400, nunca 500."
  [repo-leg relogio]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          id (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          ano (.getYear (tempo/hoje relogio zona-civil))]
      (if-not (controllers/buscar-documento-editor repo-leg ente-id id)
        (http/json-resposta 404 {:erro "documento nao encontrado"})
        (let [m (adapters-in-documento/protocolar-documento->dominio ator id (:json-params req))]
          (controllers/protocolar-documento repo-leg ente-id ano m)
          (let [{:keys [documento protocolo]} (controllers/buscar-documento-editor repo-leg ente-id id)]
            (http/json-resposta 200 (adapters-out-documento/documento->wire documento protocolo))))))))

(defn- protocolo-geral-handler
  "GET /legislativo/protocolo-geral (Onda B Slice 6, feature 3.23) — o 'Livro do Protocolo Geral' do ano
  corrente (kernel/tempo, mesmo padrao de protocolar-documento-handler)."
  [repo-leg relogio]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))
          ano (.getYear (tempo/hoje relogio zona-civil))]
      (http/json-resposta 200 (adapters-out-protocolo/livro->wire
                                 (controllers/listar-protocolo-do-ano repo-leg ente-id ano))))))

;; ========================= Onda B Slice 7: pos-aprovacao (autografo + sancao/veto, F3.8a) =========================

(defn- pos-aprovacao->wire
  "{:autografo :tramitacao-executiva} (dominio, kebab, nil-aveis) -> PosAprovacaoOut. Chama os DOIS
  adapters/out irmaos (autografo/tramitacao-executiva) antes de compor — adapters/ nunca chama outro
  adapters/ (ADR-0001 §3), mesma disciplina de ficha-materia-handler compondo detalhe->wire+ficha->wire."
  [{:keys [autografo tramitacao-executiva]}]
  (adapters-out-pos-aprovacao/pos-aprovacao->wire
    (some-> autografo adapters-out-autografo/autografo->wire)
    (some-> tramitacao-executiva adapters-out-tramitacao-executiva/tramitacao-executiva->wire)))

(defn- gerar-autografo-handler
  "POST /legislativo/proposicoes/:id/autografo — 'Gerar autografo e enviar ao Executivo'. `ano` (kernel/
  tempo, injetavel em teste) resolve o ano civil da geracao AQUI, na borda (mesmo padrao de
  protocolar-documento-handler/`ano`). nil (proposicao inexistente no tenant) -> 404. Autografo duplicado
  (guard do controller, mesmo racional de encerrar-votacao) -> :validacao/invalido -> 400 (interceptor
  global de erro)."
  [repo-leg resolver-municipio relogio]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          proposicao-id (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          ano (.getYear (tempo/hoje relogio zona-civil))
          m (adapters-in-pos-aprovacao/gerar-autografo->dominio ator proposicao-id (:json-params req))]
      (if (controllers/gerar-autografo repo-leg resolver-municipio ente-id ano m)
        (http/json-resposta 201 (pos-aprovacao->wire
                                   (controllers/buscar-pos-aprovacao repo-leg ente-id proposicao-id)))
        (http/json-resposta 404 {:erro "proposicao nao encontrada"})))))

(defn- pos-aprovacao-handler
  "GET /legislativo/proposicoes/:id/pos-aprovacao. nil (proposicao inexistente no tenant) -> 404."
  [repo-leg]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))
          proposicao-id (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-let [dados (controllers/buscar-pos-aprovacao repo-leg ente-id proposicao-id)]
        (http/json-resposta 200 (pos-aprovacao->wire dados))
        (http/json-resposta 404 {:erro "proposicao nao encontrada"})))))

(defn- registrar-resposta-executivo-handler
  "POST /legislativo/autografos/:id/resposta — 'Registrar retorno' (path :id = autografo-id). nil (sem
  tramitacao executiva para este autografo no tenant) -> 404."
  [repo-leg]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          autografo-id (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          m (adapters-in-pos-aprovacao/registrar-resposta->dominio ator (:json-params req))]
      (if (controllers/registrar-resposta-executivo repo-leg ente-id autografo-id m)
        (http/json-resposta 200 (adapters-out-tramitacao-executiva/tramitacao-executiva->wire
                                   (controllers/buscar-tramitacao-por-autografo repo-leg ente-id autografo-id)))
        (http/json-resposta 404 {:erro "tramitacao executiva nao encontrada para este autografo"})))))

(defn- apreciar-veto-handler
  "POST /legislativo/tramitacoes-executivas/:id/apreciacao (path :id = tramitacao-executiva-id, DIRETO).
  A votacao real e' aberta/encerrada via /sessoes/:id/votacoes* ja' existente (§5 doc-mestre) — esta rota
  so' carimba o desfecho. nil (tramitacao executiva inexistente no tenant) -> 404."
  [repo-leg]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          id (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          m (adapters-in-pos-aprovacao/apreciar-veto->dominio ator (:json-params req))]
      (if (controllers/apreciar-veto repo-leg ente-id id m)
        (http/json-resposta 200 (adapters-out-tramitacao-executiva/tramitacao-executiva->wire
                                   (controllers/buscar-tramitacao-executiva repo-leg ente-id id)))
        (http/json-resposta 404 {:erro "tramitacao executiva nao encontrada"})))))

;; ========================= Onda C1: borda /meu do vereador (home fora-de-sessao) =========================

(defn- meu-painel-handler
  "GET /meu/painel. Gate grosso 'vereador' na rota; `resolver-vereador` (injetado pelo host) resolve o
  vereador do proprio ator — anti-forja por construcao (nada no request escolhe 'de quem' e' o painel).
  Ator sem cadastro vinculado -> painel vazio (200), nunca 404/500 (mesmo contrato do controller)."
  [repo-leg resolver-vereador]
  (fn [req]
    (http/json-resposta 200 (adapters-out-meu-painel/meu-painel->wire
                               (controllers/meu-painel repo-leg resolver-vereador (:ator req))))))

(defn- acusar-ciencia-handler
  "POST /meu/ciencias. `vereador-id` NUNCA vem do corpo (adapters/in nem o le); o controller injeta o
  resolvido do ator. nil (ator sem cadastro vinculado -> `resolver-vereador` nil) -> 404, mesmo contrato de
  um recurso ausente do proprio ator (nunca 500)."
  [repo-leg resolver-vereador]
  (fn [req]
    (let [m (adapters-in-ciencia/acusar-ciencia->dominio (:json-params req))]
      (if-let [recibo (controllers/acusar-ciencia repo-leg resolver-vereador (:ator req) m)]
        (http/json-resposta 201 (adapters-out-meu-painel/acusar-ciencia->wire recibo))
        (http/json-resposta 404 {:erro "vereador sem cadastro vinculado neste ente"})))))

(defn rotas
  "Fragmento de rotas da votacao ao vivo + proposicoes + editor de parecer + borda /meu do vereador (table
  syntax Pedestal). Recebe o interceptor `auth` (compartilhado), o `repo-legislativo` (Repo-Component do
  proprio modulo), `consultar-sessao` (injetada pelo host — cross-modulo p/ a authz herdada da sessao),
  `resolver-municipio` (injetada pelo host — cross-modulo p/ o legislativo computar a URN em protocolar!,
  Onda B Slice 2, §22.10), `resolver-vereador` (injetada pelo host — cross-modulo p/ cadastros, Onda C1,
  §22.5.3 exceção nomeada — resolve identidade->vereador-id NESTA Casa p/ a borda /meu), `registro`
  (RegistroFatos do motor, injetado pelo host — Onda B Slice 5, o editor de parecer dirige o motor via
  emitir-parecer!) e `relogio` (kernel/tempo, injetado pelo host — review MEDIUM fe-11-parecer, mesmo
  contrato de `participacao-http/rotas`: producao le o relogio do sistema, teste crava o instante).
  Todas as acoes das verticais de votacao/proposicoes/parecer EXIGEM a authz GROSSA (papel 'secretario') +
  corpo-json nas de escrita; a fina da votacao decide no controller com a sessao carregada. A borda /meu
  EXIGE papel 'vereador' (papel DISTINTO — nao 'secretario')."
  [{:keys [auth repo-legislativo consultar-sessao resolver-municipio resolver-vereador registro relogio]}]
  (let [papel (it/exige-papel "secretario")
        papel-vereador (it/exige-papel "vereador")]
    #{["/sessoes/:id/votacoes" :post
       [auth papel it/corpo-json (abrir-handler repo-legislativo consultar-sessao)]
       :route-name :legislativo/abrir-votacao]
      ["/sessoes/:id/votacoes/:votacao-id/votos" :post
       [auth papel it/corpo-json (voto-handler repo-legislativo consultar-sessao)]
       :route-name :legislativo/registrar-voto]
      ["/sessoes/:id/votacoes/:votacao-id/meu-voto" :post
       [auth papel-vereador it/corpo-json (meu-voto-handler repo-legislativo consultar-sessao resolver-vereador registro relogio)]
       :route-name :legislativo/meu-voto]
      ["/sessoes/:id/votacoes/:votacao-id/encerramento" :post
       [auth papel it/corpo-json (encerrar-handler repo-legislativo consultar-sessao)]
       :route-name :legislativo/encerrar-votacao]
      ["/legislativo/proposicoes" :get [auth papel (listar-proposicoes-handler repo-legislativo)]
       :route-name :legislativo/listar-proposicoes]
      ["/legislativo/proposicoes" :post
       [auth papel it/corpo-json (criar-proposicao-handler repo-legislativo resolver-municipio)]
       :route-name :legislativo/criar-proposicao]
      ["/legislativo/proposicoes/:id" :get [auth papel (detalhe-proposicao-handler repo-legislativo)]
       :route-name :legislativo/detalhe-proposicao]
      ["/legislativo/proposicoes/:id/ficha" :get [auth papel (ficha-materia-handler repo-legislativo)]
       :route-name :legislativo/ficha-materia]
      ["/legislativo/proposicoes/:id" :patch
       [auth papel it/corpo-json (editar-proposicao-handler repo-legislativo)]
       :route-name :legislativo/editar-proposicao]
      ["/legislativo/pareceres/:id" :get [auth papel (parecer-editor-handler repo-legislativo)]
       :route-name :legislativo/parecer-editor]
      ["/legislativo/pareceres/:id" :patch
       [auth papel it/corpo-json (salvar-rascunho-parecer-handler repo-legislativo)]
       :route-name :legislativo/salvar-rascunho-parecer]
      ["/legislativo/pareceres/:id/emissao" :post
       [auth papel it/corpo-json (emitir-parecer-handler repo-legislativo registro relogio)]
       :route-name :legislativo/emitir-parecer]
      ["/legislativo/documento-modelos" :get [auth papel (listar-modelos-documento-handler repo-legislativo)]
       :route-name :legislativo/listar-modelos-documento]
      ["/legislativo/documentos" :post
       [auth papel it/corpo-json (gerar-documento-handler repo-legislativo)]
       :route-name :legislativo/gerar-documento]
      ["/legislativo/documentos/:id" :get [auth papel (detalhe-documento-handler repo-legislativo)]
       :route-name :legislativo/detalhe-documento]
      ["/legislativo/documentos/:id" :patch
       [auth papel it/corpo-json (editar-documento-handler repo-legislativo)]
       :route-name :legislativo/editar-documento]
      ["/legislativo/documentos/:id/protocolo" :post
       [auth papel it/corpo-json (protocolar-documento-handler repo-legislativo relogio)]
       :route-name :legislativo/protocolar-documento]
      ["/legislativo/protocolo-geral" :get [auth papel (protocolo-geral-handler repo-legislativo relogio)]
       :route-name :legislativo/protocolo-geral]
      ["/legislativo/proposicoes/:id/autografo" :post
       [auth papel it/corpo-json (gerar-autografo-handler repo-legislativo resolver-municipio relogio)]
       :route-name :legislativo/gerar-autografo]
      ["/legislativo/proposicoes/:id/pos-aprovacao" :get [auth papel (pos-aprovacao-handler repo-legislativo)]
       :route-name :legislativo/pos-aprovacao]
      ["/legislativo/autografos/:id/resposta" :post
       [auth papel it/corpo-json (registrar-resposta-executivo-handler repo-legislativo)]
       :route-name :legislativo/registrar-resposta-executivo]
      ["/legislativo/tramitacoes-executivas/:id/apreciacao" :post
       [auth papel it/corpo-json (apreciar-veto-handler repo-legislativo)]
       :route-name :legislativo/apreciar-veto]
      ["/meu/painel" :get [auth papel-vereador (meu-painel-handler repo-legislativo resolver-vereador)]
       :route-name :legislativo/meu-painel]
      ["/meu/ciencias" :post
       [auth papel-vereador it/corpo-json (acusar-ciencia-handler repo-legislativo resolver-vereador)]
       :route-name :legislativo/acusar-ciencia]}))

;; ========================= FE Onda A1: fila de relatores pendentes (§16.11) =========================

(defn relatores-pendentes-wire
  "Ponto de entrada IN-PROCESS da fila de relatores pendentes (FE Onda A1) — gemeo nao-HTTP p/ o host compor
  o dashboard da Mesa (mirror `painel-wire` de compliance). Passa pelo controller (nunca pelo Repo-Component
  direto — ADR-0001) + o MESMO gate adapters/out (projeta+valida) que uma rota HTTP usaria.

  CONVENCAO DE AUTHZ (mesmo contrato de `compliance.diplomat.http.in/painel-wire`, `sessoes.diplomat.http.in/
  presenca-resumo-wire` e `participacao.diplomat.http.in/esic-cumprimento-wire`): esta fn NAO re-verifica
  papel/permissao; o ENDPOINT COMPONHEDOR e' o unico ponto de enforcement (GET /paineis/mesa, wired numa task
  posterior, ja' exige papel 'secretario'). QUALQUER novo caller DEVE aplicar o gate 'secretario' antes —
  senao expoe a fila de relatores pendentes tenant-wide a um papel qualquer. Nao ha lint que force isso: e'
  convencao, mantida por revisao."
  [repo-legislativo ente-id]
  (adapters-out-relator/relatores-pendentes->wire (controllers/relatores-pendentes repo-legislativo ente-id)))
