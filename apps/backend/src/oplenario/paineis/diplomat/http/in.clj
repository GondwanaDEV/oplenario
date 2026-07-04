(ns oplenario.paineis.diplomat.http.in
  "Fronteira de IO HTTP de ENTRADA do modulo paineis (§22.10 diplomat/http/in, ADR-0001) — as rotas-dado
  Pedestal + os handlers (F7 Slice 1: o painel 'o que vence', §16.11). O diplomat e' a UNICA camada que
  atravessa o gate de borda: chama adapters/out (models->wire) na saida; o controller trabalha so' em
  models. Le o `ator` (posto pela cadeia de auth em (:request :ator)), nunca fala com o Repo direto (depende
  do Repo-Component, injetado por closure via `rotas`)."
  (:require [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.paineis.adapters.out.pendencia :as adapters-out]
            [oplenario.paineis.controllers :as controllers]))

(set! *warn-on-reflection* true)

(defn- pendencias-handler
  "GET /paineis/pendencias. O controller le' o read-model do tenant do ator; adapters/out projeta+valida."
  [repo-paineis]
  (fn [req]
    (http/json-resposta 200 (adapters-out/o-que-vence->wire
                             (controllers/o-que-vence repo-paineis (:ator req))))))

(defn rotas
  "Fragmento de rotas do modulo paineis (table syntax Pedestal). Recebe o interceptor `auth` (compartilhado)
  + o `repo-paineis` (Repo-Component) e devolve as rotas-dado. `oplenario.rotas` funde este fragmento ao
  conjunto. Authz GROSSA (papel 'secretario' — mesmo papel interno de compliance/sessoes/legislativo/
  participacao) — o painel e' tenant-wide read-model, sem recurso unico p/ camada fina."
  [{:keys [auth repo-paineis]}]
  (let [papel (it/exige-papel "secretario")]
    #{["/paineis/pendencias" :get [auth papel (pendencias-handler repo-paineis)]
       :route-name :paineis/pendencias]}))
