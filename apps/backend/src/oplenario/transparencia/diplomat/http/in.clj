(ns oplenario.transparencia.diplomat.http.in
  "Fronteira de IO HTTP de ENTRADA do modulo transparencia (§22.10 diplomat/http/in, ADR-0001, F6c Slice 1) —
  as rotas-dado Pedestal + os handlers do PORTAL PUBLICO. TODAS as rotas sao PUBLICAS (SEM `auth`) — o
  :ente do path resolve via `resolver-ente-publico` (seam do host, mesmo mecanismo de participacao) e o
  Repo abre com-tenant* com ele (a RLS ISOLA mesmo sem ator); a saida FILTRA via adapters/out.

  Disambiguador `casa/` (mesma razao ja documentada em participacao): o router prefix-tree do Pedestal 0.7
  nao admite um wildcard (`:ente`) e um literal no MESMO nivel de path — `casa/` mantem depth-2 sempre
  literal, o :ente cai em subarvore propria."
  (:require [oplenario.http :as http]
            [oplenario.transparencia.adapters.in.portal :as adapters-in]
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
  [repo-transparencia resolver-ente-publico]
  (fn [req]
    (let [ente-id (resolver-ente-publico (get-in req [:path-params :ente]))]
      (http/json-resposta 200
        (adapters-out-norma/->wires (controllers/listar-normas repo-transparencia ente-id))))))

(defn- buscar-norma-handler
  [repo-transparencia resolver-ente-publico]
  (fn [req]
    (let [ente-id  (resolver-ente-publico (get-in req [:path-params :ente]))
          norma-id (adapters-in/norma-param->uuid (get-in req [:path-params :norma_id]))]
      (if-let [n (controllers/buscar-norma repo-transparencia ente-id norma-id)]
        (http/json-resposta 200 (adapters-out-norma/->wire n))
        (http/json-resposta 404 {:erro "norma nao encontrada"})))))

(defn rotas
  "Fragmento de rotas do modulo transparencia (table syntax Pedestal). Recebe o `repo-transparencia`
  (Repo-Component) + o `resolver-ente-publico` (seam do host). `oplenario.rotas` funde este fragmento."
  [{:keys [repo-transparencia resolver-ente-publico]}]
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
     :route-name :transparencia/buscar-norma]})
