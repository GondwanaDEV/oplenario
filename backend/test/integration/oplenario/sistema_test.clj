(ns oplenario.sistema-test
  "Integracao: o sistema Component sobe e expoe a infra do kernel ja conectada (§22.10: host =
  merge dos sub-systems + infra do kernel). F0.1 fia o minimo: datasource. F0.2+ adicionam outbox-relay etc."
  (:require [clojure.test :refer [deftest is]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.sistema :as sistema]
            [next.jdbc :as jdbc]))

(deftest sistema-boota-e-expoe-datasource-conectado
  (let [sys (component/start (sistema/novo-sistema (config/carregar)))]
    (try
      (is (= {:um 1} (jdbc/execute-one! (-> sys :datasource :ds) ["SELECT 1 AS um"]))
          "o sistema bootado expoe um datasource conectado ao Postgres")
      (finally (component/stop sys)))))
