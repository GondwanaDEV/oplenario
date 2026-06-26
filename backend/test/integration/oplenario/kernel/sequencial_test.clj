(ns oplenario.kernel.sequencial-test
  "Numeracao canonica gapless por linha-contador (§22.9 Eixo 2): comeca em 1, incrementa por escopo,
  e' TENANT-scoped (ente do GUC via com-tenant*) e NAO deixa buraco no rollback. Postgres real."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.sequencial :as sequencial]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(use-fixtures :each
  (fn [t] (jdbc/execute! *ds* ["TRUNCATE shared.sequencial"]) (t)))

(defn- prox [ente escopo]
  (tenancy/com-tenant* *ds* ente (fn [tx] (sequencial/proximo! tx escopo))))

(deftest sequencial-comeca-em-1-e-incrementa-por-escopo
  (let [ente (random-uuid)]
    (is (= 1 (prox ente "lei:2026")) "1o do escopo")
    (is (= 2 (prox ente "lei:2026")) "2o do escopo")
    (is (= 1 (prox ente "decreto:2026")) "escopo distinto comeca em 1")
    (is (= 1 (prox (random-uuid) "lei:2026")) "outro ente tem contador proprio (RLS + ente_id no GUC)")))

(deftest sequencial-e-gapless-no-rollback
  (let [ente (random-uuid)]
    (is (= 1 (prox ente "x")) "commit -> 1")
    (try
      (tenancy/com-tenant* *ds* ente
                           (fn [tx]
                             (sequencial/proximo! tx "x")     ; tomaria 2, mas a tx reverte
                             (throw (ex-info "rollback proposital" {}))))
      (catch clojure.lang.ExceptionInfo _ nil))
    (is (= 2 (prox ente "x")) "apos rollback do '2', o proximo e' 2 de novo — sem buraco (gapless)")))
