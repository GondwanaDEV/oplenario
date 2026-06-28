(ns oplenario.kernel.components.datasource-test
  "Integracao: o pool Hikari (Component) abre no start e fala com o Postgres real (§22.10:
  db testa contra banco Dockerizado, nao fake-DB). Roda com DATABASE_URL apontando p/ o PG de teste
  (local: host:5544; default do config.edn: 5432)."
  (:require [clojure.test :refer [deftest is]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as ds]
            [next.jdbc :as jdbc]))

(deftest datasource-abre-pool-e-consulta-o-postgres
  (let [c (component/start (ds/datasource (config/carregar)))]
    (try
      (is (= {:um 1} (jdbc/execute-one! (:ds c) ["SELECT 1 AS um"]))
          "o pool consulta o Postgres real")
      (finally (component/stop c)))))

(deftest datasource-fecha-o-pool-no-stop
  (let [c      (component/start (ds/datasource (config/carregar)))
        pool   (:ds c)
        parado (component/stop c)]
    (is (nil? (:ds parado)) "stop limpa o datasource do componente")
    (is (.isClosed ^com.zaxxer.hikari.HikariDataSource pool)
        "o pool Hikari foi efetivamente fechado (nao so o campo limpo)")))
