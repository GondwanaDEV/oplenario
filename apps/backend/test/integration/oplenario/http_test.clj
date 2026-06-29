(ns oplenario.http-test
  "W1 (frente wire/HTTP): esqueleto do serviço Pedestal do host. Prova a rota /saude via `response-for` (sem
  bind de porta) + o ciclo de vida do Component ServidorHttp (start sobe o Jetty, stop derruba) numa porta
  EFEMERA (0) p/ nao colidir com o app/dev. O servidor fica FORA de novo-sistema (so em sistema-serve) p/ os
  testes de boot do dominio nao subirem o Jetty."
  (:require [clojure.test :refer [deftest is]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.kernel.components.http-servidor :as servidor]))

(def ^:private service-fn
  (-> (http/servico (config/carregar) http/rotas-saude) ph/create-server ::ph/service-fn))

(deftest saude-responde-ok
  (let [r (pt/response-for service-fn :get "/saude")]
    (is (= 200 (:status r)) "GET /saude -> 200")
    (is (= "ok" (:status (json/read-value (:body r) json/keyword-keys-object-mapper)))
        "corpo JSON {:status \"ok\"}")))

(deftest rota-inexistente-404
  (is (= 404 (:status (pt/response-for service-fn :get "/nao-existe"))) "rota desconhecida -> 404"))

(deftest servidor-lifecycle
  (let [cfg (assoc-in (config/carregar) [:http :port] 0)   ; porta efemera: sem conflito em teste
        s   (component/start (servidor/servidor-http cfg http/rotas-saude))]
    (is (some? (:servidor s)) "start sobe o Jetty")
    (is (nil? (:servidor (component/stop s))) "stop libera o Jetty")))
