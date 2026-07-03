(ns oplenario.transparencia.diplomat.http.in
  "Fronteira de IO HTTP de ENTRADA do modulo transparencia (§22.10 diplomat/http/in, ADR-0001) — as rotas-dado
  Pedestal + os handlers.

  DOIS perfis: (Slice 1) o PORTAL PUBLICO (SEM `auth`) — o :ente do path resolve via `resolver-ente-publico`
  (seam do host) e o Repo abre com-tenant* com ele (RLS isola mesmo sem ator); a saida FILTRA via adapters/out.
  (Slice 2) o ACOMPANHAMENTO do cidadao (COM `auth`) — o ente-id + seguidor vem do ATOR (token), NUNCA do
  path/corpo (anti-forge); mesmo perfil so-auth-sem-papel de participacao/comentar.

  Disambiguador `casa/` (mesma razao ja documentada em participacao): o router prefix-tree do Pedestal 0.7
  nao admite um wildcard (`:ente`) e um literal no MESMO nivel de path — `casa/` mantem depth-2 sempre
  literal, o :ente cai em subarvore propria. As rotas do Slice 2 sao sob /portal (autenticadas, sem :ente no
  path) — sem colisao com as publicas /portal/casa/... ."
  (:require [oplenario.http :as http]
            [oplenario.transparencia.adapters.in.portal :as adapters-in]
            [oplenario.transparencia.adapters.out.acompanhamento :as adapters-out-acomp]
            [oplenario.transparencia.adapters.out.materia :as adapters-out-materia]
            [oplenario.transparencia.adapters.out.norma :as adapters-out-norma]
            [oplenario.transparencia.controllers :as controllers]))

(set! *warn-on-reflection* true)

(def resolver-ente-publico-uuid
  "Seam `resolver-ente-publico` DEFAULT do host (V1): o :ente do path = UUID do ente, coagido fail-closed
  (:validacao/invalido -> 400). Mesmo seam de participacao/diplomat/http/in — fornecido pelo host a `rotas`."
  adapters-in/ente-param->uuid)

(defn- listar-materias-handler
  [repo-transparencia resolver-ente-publico]
  (fn [req]
    (let [ente-id (resolver-ente-publico (get-in req [:path-params :ente]))]
      (http/json-resposta 200
        (adapters-out-materia/->wires (controllers/listar-materias repo-transparencia ente-id))))))

(defn- ficha-materia-handler
  [repo-transparencia resolver-ente-publico]
  (fn [req]
    (let [ente-id       (resolver-ente-publico (get-in req [:path-params :ente]))
          proposicao-id (adapters-in/proposicao-param->uuid (get-in req [:path-params :proposicao_id]))]
      (if-let [detalhe (controllers/ficha-materia repo-transparencia ente-id proposicao-id)]
        (http/json-resposta 200 (adapters-out-materia/ficha->wire detalhe (:norma detalhe)))
        (http/json-resposta 404 {:erro "materia nao encontrada"})))))

(defn- listar-normas-handler
  "GET /portal/casa/:ente/legislacao(?tipo=&ano=&numero=) — acervo as-enacted (F6c Slice 3). Query-params
  OPCIONAIS coagidos na borda (ano/numero nao-inteiro -> 400); ausentes -> filtro vazio = compat Slice 1."
  [repo-transparencia resolver-ente-publico]
  (fn [req]
    (let [ente-id (resolver-ente-publico (get-in req [:path-params :ente]))
          filtro  (adapters-in/filtro-legislacao (:query-params req))]
      (http/json-resposta 200
        (adapters-out-norma/->wires (controllers/listar-normas repo-transparencia ente-id filtro))))))

(defn- buscar-norma-handler
  [repo-transparencia resolver-ente-publico]
  (fn [req]
    (let [ente-id  (resolver-ente-publico (get-in req [:path-params :ente]))
          norma-id (adapters-in/norma-param->uuid (get-in req [:path-params :norma_id]))]
      (if-let [n (controllers/buscar-norma repo-transparencia ente-id norma-id)]
        (http/json-resposta 200 (adapters-out-norma/->wire n))
        (http/json-resposta 404 {:erro "norma nao encontrada"})))))

(defn- seguir-handler
  "POST /portal/materias/:proposicao_id/acompanhar (cidadao, SO-auth). ente-id + seguidor do ATOR; guard de
  existencia da materia no controller (ausente -> nil -> 404). Devolve 201 {estado} (UPSERT; re-seguir 201 tb)."
  [repo-transparencia]
  (fn [req]
    (let [proposicao-id (adapters-in/proposicao-param->uuid (get-in req [:path-params :proposicao_id]))]
      (if-let [r (controllers/seguir! repo-transparencia (:ator req) proposicao-id)]
        (http/json-resposta 201 (adapters-out-acomp/recibo->wire (:estado r)))
        (http/json-resposta 404 {:erro "materia nao encontrada"})))))

(defn- deixar-de-seguir-handler
  "DELETE /portal/materias/:proposicao_id/acompanhar (cidadao, SO-auth). IDEMPOTENTE: 200 {cancelado} mesmo
  se nao seguia (retirar consentimento e' sempre seguro)."
  [repo-transparencia]
  (fn [req]
    (let [proposicao-id (adapters-in/proposicao-param->uuid (get-in req [:path-params :proposicao_id]))]
      (controllers/deixar-de-seguir! repo-transparencia (:ator req) proposicao-id)
      (http/json-resposta 200 (adapters-out-acomp/recibo->wire "cancelado")))))

(defn- meus-acompanhamentos-handler
  "GET /portal/acompanhamentos (cidadao, SO-auth). Lista as materias que o ATOR segue (escopo pelo seguidor
  do token — nunca ve as de outro)."
  [repo-transparencia]
  (fn [req]
    (http/json-resposta 200
      (adapters-out-acomp/minhas->wire (controllers/meus-acompanhamentos repo-transparencia (:ator req))))))

(defn rotas
  "Fragmento de rotas do modulo transparencia (table syntax Pedestal). Recebe o `repo-transparencia`
  (Repo-Component), o `resolver-ente-publico` (seam do host, rotas publicas do Slice 1) e o interceptor
  `auth` (compartilhado, rotas autenticadas do Slice 2). `oplenario.rotas` funde este fragmento."
  [{:keys [repo-transparencia resolver-ente-publico auth]}]
  #{["/portal/casa/:ente/materias" :get
     [(listar-materias-handler repo-transparencia resolver-ente-publico)]
     :route-name :transparencia/listar-materias]
    ["/portal/casa/:ente/materias/:proposicao_id" :get
     [(ficha-materia-handler repo-transparencia resolver-ente-publico)]
     :route-name :transparencia/ficha-materia]
    ["/portal/casa/:ente/legislacao" :get
     [(listar-normas-handler repo-transparencia resolver-ente-publico)]
     :route-name :transparencia/listar-normas]
    ["/portal/casa/:ente/legislacao/:norma_id" :get
     [(buscar-norma-handler repo-transparencia resolver-ente-publico)]
     :route-name :transparencia/buscar-norma]
    ;; ---- Slice 2: acompanhamento do cidadao (autenticado, SO-auth sem papel) ----
    ["/portal/materias/:proposicao_id/acompanhar" :post
     [auth (seguir-handler repo-transparencia)]
     :route-name :transparencia/seguir]
    ["/portal/materias/:proposicao_id/acompanhar" :delete
     [auth (deixar-de-seguir-handler repo-transparencia)]
     :route-name :transparencia/deixar-de-seguir]
    ["/portal/acompanhamentos" :get
     [auth (meus-acompanhamentos-handler repo-transparencia)]
     :route-name :transparencia/meus-acompanhamentos]})
