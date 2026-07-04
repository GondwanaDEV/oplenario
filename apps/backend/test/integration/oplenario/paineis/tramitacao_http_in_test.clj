(ns oplenario.paineis.tramitacao-http-in-test
  "F7 Slice 2 (borda HTTP do paineis) — a vertical de rota do board de tramitacao (§16.11): prova a
  silhueta de borda end-to-end (controller -> repo -> adapters/out -> wire/out) + a authz grossa
  (exige-papel) + 401. DB-free: RepoPaineis FAKE (reify) + idp-dev real (precedente
  pendencias-http-in-test)."
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.paineis.components.repositorio :as repo-paineis]
            [oplenario.rotas :as rotas]))

(defn- fake-repo-paineis
  "RepoPaineis fake: `tramitacao-board` devolve `resultado`. Impl parcial proposital (so o metodo exercido)."
  [resultado]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-paineis/RepoPaineis
    (tramitacao-board [_ _ente-id] resultado)))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- service-fn [papeis repo-p]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-paineis repo-p})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id]
  (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))

(defn- com-bearer [tok] {"authorization" (str "Bearer " tok)})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(defn- item-canonico [ente]
  {:ente-id ente :proposicao-id (random-uuid) :tipo "pl" :ano 2026 :sequencial 1
   :urn-lex "urn:lex:br:camara:pl:2026;1" :ementa "dispoe sobre teste"
   :autor-tipo "vereador" :autor-texto "Fulano" :estado "em_comissao"
   :projetado-em (java.time.Instant/now) :transicionou-em (java.time.Instant/now)})

;; ---------- GET /paineis/tramitacao ----------

(deftest tramitacao-200
  (let [ente (random-uuid)
        r (pt/response-for (service-fn #{"secretario"} (fake-repo-paineis [(item-canonico ente)]))
                           :get "/paineis/tramitacao" :headers (com-bearer (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)) "GET /paineis/tramitacao com papel secretario -> 200")
    (is (= 1 (count (:itens body))))
    (let [i (first (:itens body))]
      (is (= "pl" (:tipo i)))
      (is (= "em_comissao" (:estado i)))
      (is (string? (:proposicao-id i)) "proposicao-id como string")
      (is (not (contains? i :ente-id)) "ente-id (tenant) nao vaza"))))

(deftest tramitacao-vazio-200
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-paineis []))
                           :get "/paineis/tramitacao" :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 200 (:status r)))
    (is (= [] (:itens (ler-json r))))))

(deftest tramitacao-sem-papel-403
  (let [r (pt/response-for (service-fn #{"vereador"} (fake-repo-paineis []))
                           :get "/paineis/tramitacao" :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 403 (:status r)) "ator sem papel 'secretario' -> authz grossa nega -> 403")))

(deftest tramitacao-sem-token-401
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-paineis []))
                           :get "/paineis/tramitacao")]
    (is (= 401 (:status r)) "rota de modulo herda a cadeia de auth: sem token -> 401 (fail-closed)")))
