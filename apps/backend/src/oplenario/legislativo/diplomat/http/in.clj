(ns oplenario.legislativo.diplomat.http.in
  "Fronteira de IO HTTP de ENTRADA do legislativo (§22.10 diplomat/http/in, ADR-0001): DUAS verticais moram
  aqui. (1) a votacao ao vivo (F4 Slice 3) — abrir / registrar voto / encerrar. A rota mora AQUI (legislativo
  e' o DONO do agregado votacao + da tx que casa ato+emissao, Slice 1), nao no `sessoes` — espelha o SSE
  `/sessoes/:id/plenario` que mora no `tempo_real` (prefixo de URL != dono do modulo). (2) GET
  /legislativo/proposicoes (Onda B Slice 1) — a listagem tenant-wide de proposicoes, so' o gate grosso (papel
  'secretario'), sem authz herdada de sessao. O diplomat e' a UNICA camada que cruza o gate de borda
  (adapters/in na entrada, adapters/out na saida); o controller trabalha so em models. Na vertical de votacao a
  authz e' HERDADA do recurso SESSAO via `consultar-sessao` INJETADA pelo host (legislativo NAO importa
  sessoes, §22.10): a grossa (exige-papel) na rota, a fina (policy.check/pode-dirigir-votacao?) no controller."
  (:require [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.legislativo.adapters.in.proposicao :as adapters-in-proposicao]
            [oplenario.legislativo.adapters.in.votacao :as adapters-in]
            [oplenario.legislativo.adapters.out.proposicao :as adapters-out-proposicao]
            [oplenario.legislativo.adapters.out.relator-pendente :as adapters-out-relator]
            [oplenario.legislativo.adapters.out.votacao :as adapters-out]
            [oplenario.legislativo.controllers :as controllers]))

(set! *warn-on-reflection* true)

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

(defn rotas
  "Fragmento de rotas da votacao ao vivo + proposicoes (table syntax Pedestal). Recebe o interceptor `auth`
  (compartilhado), o `repo-legislativo` (Repo-Component do proprio modulo), `consultar-sessao` (injetada pelo
  host — cross-modulo p/ a authz herdada da sessao) e `resolver-municipio` (injetada pelo host — cross-modulo
  p/ o legislativo computar a URN em protocolar!, Onda B Slice 2, §22.10). Todas as acoes EXIGEM a authz
  GROSSA (papel 'secretario') + corpo-json nas de escrita; a fina da votacao decide no controller com a
  sessao carregada."
  [{:keys [auth repo-legislativo consultar-sessao resolver-municipio]}]
  (let [papel (it/exige-papel "secretario")]
    #{["/sessoes/:id/votacoes" :post
       [auth papel it/corpo-json (abrir-handler repo-legislativo consultar-sessao)]
       :route-name :legislativo/abrir-votacao]
      ["/sessoes/:id/votacoes/:votacao-id/votos" :post
       [auth papel it/corpo-json (voto-handler repo-legislativo consultar-sessao)]
       :route-name :legislativo/registrar-voto]
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
      ["/legislativo/proposicoes/:id" :patch
       [auth papel it/corpo-json (editar-proposicao-handler repo-legislativo)]
       :route-name :legislativo/editar-proposicao]}))

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
