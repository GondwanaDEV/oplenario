(ns oplenario.compliance.diplomat.http.in
  "Fronteira de IO HTTP de ENTRADA do modulo compliance (§22.10 diplomat/http/in, ADR-0001) — as rotas-dado
  Pedestal + os handlers (F5.5, a borda do compliance). O diplomat e' a UNICA camada que atravessa o gate de
  borda: chama adapters/out (models->wire) na saida; o controller trabalha so em models. Le o `ator` (posto
  pela cadeia de auth em (:request :ator)), nunca fala com db/ direto (depende do Repo-Component, injetado
  por closure via `rotas`). A authz GROSSA (exige-papel) entra na rota; o painel e' tenant-wide read-model.

  F5.5a entrega o PAINEL (read). F5.5b entrega o CICLO DA REMESSA (POST validar/submeter/registrar-resposta);
  a geracao/avaliacao sob_demanda e' F5.5c (gated: descritor de layout = [GAP], Components serializador/fontes
  ainda nao fiados no system-map)."
  (:require [oplenario.compliance.adapters.in.remessa :as adapters-in-remessa]
            [oplenario.compliance.adapters.out.painel :as adapters-out-painel]
            [oplenario.compliance.adapters.out.remessa :as adapters-out-remessa]
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

(defn- responder-transicao
  "Resposta comum das transicoes do ciclo da remessa: a remessa transicionada -> 200 (adapters/out projeta+
  filtra); nil (inexistente no tenant) -> 404; ExceptionInfo :conflito/remessa (estado incompativel / CAS
  perdido) -> 409 (nao 500, espelha o transicionar-handler de sessoes); outra excecao re-lancada (-> 500
  global). A validacao de borda (id/estado) ja lancou ANTES (fora do try) -> 400 global."
  [transicionar!]
  (try
    (if-let [r (transicionar!)]
      (http/json-resposta 200 (adapters-out-remessa/remessa->wire r))
      (http/json-resposta 404 {:erro "remessa nao encontrada"}))
    (catch clojure.lang.ExceptionInfo e
      (if (= :conflito/remessa (:tipo (ex-data e)))
        (http/json-resposta 409 {:erro "remessa em estado incompativel com a transicao"})
        (throw e)))))

(defn- validar-remessa-handler
  "POST /compliance/remessas/:id/validar (rascunho->validada). adapters/in coage o :id (-> 400 se malformado)."
  [repo-compliance]
  (fn [req]
    (let [id (adapters-in-remessa/id-param->uuid (get-in req [:path-params :id]))]
      (responder-transicao #(controllers/validar-remessa repo-compliance (:ator req) id)))))

(defn- submeter-remessa-handler
  "POST /compliance/remessas/:id/submeter (validada->submetida)."
  [repo-compliance]
  (fn [req]
    (let [id (adapters-in-remessa/id-param->uuid (get-in req [:path-params :id]))]
      (responder-transicao #(controllers/submeter-remessa repo-compliance (:ator req) id)))))

(defn- registrar-resposta-handler
  "POST /compliance/remessas/:id/resposta (submetida->{aceita|rejeitada}). adapters/in coage o :id + valida
  o corpo {estado} (-> 400 se ausente/fora de aceita|rejeitada). So 'aceita' cumpre a obrigacao."
  [repo-compliance]
  (fn [req]
    (let [id     (adapters-in-remessa/id-param->uuid (get-in req [:path-params :id]))
          estado (adapters-in-remessa/resposta->estado (:json-params req))]
      (responder-transicao #(controllers/registrar-resposta-remessa repo-compliance (:ator req) id estado)))))

(defn rotas
  "Fragmento de rotas do modulo compliance (table syntax Pedestal). Recebe o interceptor `auth` (compartilhado)
  + o `repo-compliance` (Repo-Component) e devolve as rotas-dado. `oplenario.rotas` funde este fragmento ao
  conjunto. Tudo exige a authz GROSSA (papel 'secretario' — a tela do comprador/servidor na POC; o refino de
  papel [presidente/juridico] e' carry de roles). O ciclo da remessa usa `corpo-json` (corpo pequeno; o de
  validar/submeter e' vazio e o interceptor passa direto)."
  [{:keys [auth repo-compliance]}]
  (let [papel (it/exige-papel "secretario")]
    #{["/compliance/painel" :get [auth papel (painel-handler repo-compliance)]
       :route-name :compliance/painel]
      ["/compliance/remessas/:id/validar" :post
       [auth papel it/corpo-json (validar-remessa-handler repo-compliance)]
       :route-name :compliance/validar-remessa]
      ["/compliance/remessas/:id/submeter" :post
       [auth papel it/corpo-json (submeter-remessa-handler repo-compliance)]
       :route-name :compliance/submeter-remessa]
      ["/compliance/remessas/:id/resposta" :post
       [auth papel it/corpo-json (registrar-resposta-handler repo-compliance)]
       :route-name :compliance/resposta-remessa]}))
