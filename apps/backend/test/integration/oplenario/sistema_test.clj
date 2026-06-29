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

(deftest backplane-de-tempo-real-invalido-lanca
  ;; review sec-MINOR-1: um typo em TEMPO_REAL_BACKPLANE (ex.: :Valkey) cairia em :memoria em silencio — cada
  ;; replica de prod com store isolado. novo-sistema deve LANCAR no boot (antes de qualquer IO).
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"backplane de tempo real invalido"
        (sistema/novo-sistema (assoc-in (config/carregar) [:tempo-real :backplane] :bogus)))
      "backplane desconhecido bloqueia o boot (fail-closed)"))
