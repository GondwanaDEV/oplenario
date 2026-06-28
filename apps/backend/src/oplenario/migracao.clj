(ns oplenario.migracao
  "Pipeline de migrations (Migratus): aplica resources/migrations/* contra o datasource injetado.
  Schema-por-modulo (§22.10); rodado no boot/deploy e nos testes de integracao. Idempotente."
  (:require [migratus.core :as migratus]))

(defn- config [ds]
  {:store         :database
   :migration-dir "migrations"
   :db            {:datasource ds}})

(defn migrar!
  "Aplica todas as migrations pendentes (idempotente — migratus rastreia as aplicadas)."
  [ds]
  (migratus/migrate (config ds)))
