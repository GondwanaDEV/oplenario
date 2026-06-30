(ns oplenario.compliance.diplomat.http.in
  "Fronteira de IO HTTP de ENTRADA do modulo compliance (§22.10 diplomat/http/in, ADR-0001) — as rotas-dado
  Pedestal + os handlers (F5.5, a borda do compliance). O diplomat e' a UNICA camada que atravessa o gate de
  borda: chama adapters/out (models->wire) na saida; o controller trabalha so em models. Le o `ator` (posto
  pela cadeia de auth em (:request :ator)), nunca fala com db/ direto (depende do Repo-Component, injetado
  por closure via `rotas`). A authz GROSSA (exige-papel) entra na rota; o painel e' tenant-wide read-model.

  F5.5a entrega o PAINEL (read). O ciclo da remessa (POST validar/submeter/registrar-resposta) e' F5.5b; a
  geracao/avaliacao sob_demanda e' F5.5c (gated: descritor de layout = [GAP], Components serializador/fontes
  ainda nao fiados no system-map)."
  (:require [oplenario.compliance.adapters.out.painel :as adapters-out-painel]
            [oplenario.compliance.controllers :as controllers]
            [oplenario.http :as http]
            [oplenario.interceptors :as it]))

(set! *warn-on-reflection* true)

(defn- painel-handler
  "GET /compliance/painel. O controller le o read-model do tenant do ator; adapters/out projeta+valida+filtra."
  [repo-compliance]
  (fn [req]
    (http/json-resposta 200 (adapters-out-painel/painel->wire
                             (controllers/painel repo-compliance (:ator req))))))

(defn rotas
  "Fragmento de rotas do modulo compliance (table syntax Pedestal). Recebe o interceptor `auth` (compartilhado)
  + o `repo-compliance` (Repo-Component) e devolve as rotas-dado. `oplenario.rotas` funde este fragmento ao
  conjunto. O painel exige a authz GROSSA (papel 'secretario' — a tela do comprador/servidor na POC; o
  refino de papel [presidente/juridico] e' carry de roles)."
  [{:keys [auth repo-compliance]}]
  #{["/compliance/painel" :get [auth (it/exige-papel "secretario") (painel-handler repo-compliance)]
     :route-name :compliance/painel]})
