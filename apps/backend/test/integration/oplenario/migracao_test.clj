(ns oplenario.migracao-test
  "Integracao: o pipeline Migratus aplica as migrations 1–6 contra o Postgres real e cria os
  schemas-por-modulo (§22.10). Idempotente (migratus rastreia aplicadas)."
  (:require [clojure.test :refer [deftest is]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as ds]
            [oplenario.migracao :as migracao]
            [next.jdbc :as jdbc]))

(deftest migrar-cria-os-schemas-e-tabelas
  (let [c (component/start (ds/datasource (config/carregar)))]
    (try
      (migracao/migrar! (:ds c))
      (is (true? (:existe (jdbc/execute-one! (:ds c)
                            ["SELECT EXISTS(SELECT 1 FROM information_schema.schemata WHERE schema_name=?) AS existe"
                             "legislativo"])))
          "mig 1 cria o schema legislativo")
      (is (true? (:existe (jdbc/execute-one! (:ds c)
                            ["SELECT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=? AND table_name=?) AS existe"
                             "admin_sistema" "ente"])))
          "mig 3 cria admin_sistema.ente — prova que o pipeline aplica varias migrations")
      (finally (component/stop c)))))
