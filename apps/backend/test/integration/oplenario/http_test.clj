(ns oplenario.http-test
  "W1+W2 (frente wire/HTTP). W1: esqueleto Pedestal — /saude via `response-for` (sem porta) + ciclo do Component
  ServidorHttp em porta EFEMERA. W2: a cadeia de interceptors (a borda de §22.5) — AUTENTICACAO (bearer ->
  idp/verificar-token [idp-dev, dev] -> resolver-sessao -> ator; fail-closed 401) e AUTORIZACAO GROSSA
  (exige-papel -> 403 via negado?). Testado com o idp-dev real + um RepoIdentidade fake (sem DB; resolver-sessao
  contra DB ja tem cobertura no F1.4)."
  (:require [clojure.test :refer [deftest is]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.kernel.components.http-servidor :as servidor]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.rotas :as rotas]))

;; ---------- W1: esqueleto + /saude ----------

(def ^:private service-fn-saude
  (-> (http/servico (config/carregar) http/rotas-saude) ph/create-server ::ph/service-fn))

(deftest saude-responde-ok
  (let [r (pt/response-for service-fn-saude :get "/saude")]
    (is (= 200 (:status r)) "GET /saude -> 200")
    (is (= "ok" (:status (json/read-value (:body r) json/keyword-keys-object-mapper)))
        "corpo JSON {:status \"ok\"}")))

(deftest rota-inexistente-404
  (is (= 404 (:status (pt/response-for service-fn-saude :get "/nao-existe"))) "rota desconhecida -> 404"))

(deftest servidor-lifecycle
  (let [cfg (assoc-in (config/carregar) [:http :port] 0)   ; porta efemera: sem conflito em teste
        s   (component/start (servidor/servidor-http cfg (fn [_] http/rotas-saude)))]
    (is (some? (:servidor s)) "start sobe o Jetty")
    (is (nil? (:servidor (component/stop s))) "stop libera o Jetty")))

;; ---------- W2: a cadeia de interceptors (auth + authz) ----------

(defn- fake-repo-identidade
  "RepoIdentidade fake: snapshot-ator devolve um vinculo ativo com os `papeis` dados (sem DB)."
  [papeis]
  ;; resolver-sessao so chama snapshot-ator — impl parcial proposital (fake de teste).
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- service-fn [papeis]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev) :repo-identidade (fake-repo-identidade papeis)})
                    it/globais)                              ; erro/cabecalhos sao globais (como no servidor real)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id]
  (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))

(defn- com-bearer [tok] {"authorization" (str "Bearer " tok)})

(deftest eu-sem-token-401
  (is (= 401 (:status (pt/response-for (service-fn #{}) :get "/eu"))) "sem Authorization -> 401 (fail-closed)"))

(deftest eu-token-invalido-401
  (is (= 401 (:status (pt/response-for (service-fn #{}) :get "/eu" :headers (com-bearer "nao-e-json"))))
      "token nao-decodificavel -> 401"))

(deftest eu-token-valido-200
  (let [ente (random-uuid) ident (random-uuid)
        r (pt/response-for (service-fn #{}) :get "/eu" :headers (com-bearer (token ente ident)))
        ator (:ator (json/read-value (:body r) json/keyword-keys-object-mapper))]
    (is (= 200 (:status r)) "token valido + vinculo ativo -> 200")
    (is (= (str ente) (:ente-id ator)) "o ator resolvido carrega o ente-id do token")
    (is (= (str ident) (:identidade-id ator)) "e a identidade")))

(deftest painel-papel-suficiente-200
  (let [r (pt/response-for (service-fn #{"secretario"}) :get "/painel-secretaria"
                           :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 200 (:status r)) "ator com papel 'secretario' -> 200")))

(deftest painel-papel-insuficiente-403
  (let [r (pt/response-for (service-fn #{"vereador"}) :get "/painel-secretaria"
                           :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 403 (:status r)) "ator sem o papel 'secretario' -> 403 (autorizacao negada)")))
