(ns oplenario.sessoes.diplomat.http.in
  "Fronteira de IO HTTP de ENTRADA do modulo sessoes (§22.10 diplomat/http/in, ADR-0001): as rotas-dado Pedestal
  + os handlers. O diplomat e' a UNICA camada que atravessa o gate de borda: chama adapters/in (wire->models) na
  entrada e adapters/out (models->wire) na saida; o controller trabalha so em models. Le o `ator` (posto pela
  cadeia de auth em (:request :ator)), nunca fala com db/ direto (depende do Repo-Component, injetado por closure
  via `rotas`). A authz GROSSA (exige-papel) entra na rota; a FINA (policy.check) roda no controller."
  (:require [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.sessoes.adapters.in.sessao :as adapters-in]
            [oplenario.sessoes.adapters.out.pauta :as adapters-out-pauta]
            [oplenario.sessoes.adapters.out.sessao :as adapters-out]
            [oplenario.sessoes.controllers :as controllers]))

(set! *warn-on-reflection* true)

(defn- buscar-handler
  "GET /sessoes/:id. adapters/in coage o :id; controller carrega+autoriza; adapters/out projeta. nil -> 404."
  [repo-sessoes]
  (fn [req]
    (let [ator (:ator req)
          id   (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-let [s (controllers/buscar-sessao repo-sessoes ator id)]
        (http/json-resposta 200 (adapters-out/sessao->wire s))
        (http/json-resposta 404 {:erro "sessao nao encontrada"})))))

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

(defn rotas
  "Fragmento de rotas do modulo (table syntax Pedestal). Recebe o interceptor `auth` (compartilhado) + o
  `repo-sessoes` (Repo-Component) e devolve as rotas-dado. `oplenario.rotas` funde este fragmento ao conjunto.
  POST exige a authz GROSSA (papel 'secretario') + corpo-json; GET so autentica (a camada fina decide no
  controller)."
  [{:keys [auth repo-sessoes]}]
  #{["/sessoes"     :post [auth (it/exige-papel "secretario") it/corpo-json (agendar-handler repo-sessoes)]
     :route-name :sessoes/agendar]
    ["/sessoes/:id" :get  [auth (buscar-handler repo-sessoes)] :route-name :sessoes/buscar]
    ["/sessoes/:id/pauta" :get [auth (pauta-handler repo-sessoes)] :route-name :sessoes/pauta]})
