(ns oplenario.kernel.db-tipos-test
  "Convencao Instant<->timestamptz no data layer (carry F0.3): o relogio do kernel (kernel/tempo)
  produz java.time.Instant; o data layer o LIGA como timestamptz na ida e o RECONSTROI como Instant
  na volta — sem java.sql.Timestamp vazando pro dominio nem ambiguidade de zona. PG real."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.db-tipos])           ; carrega as extensoes de protocolo no processo
  (:import (java.time Instant)))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(deftest instant-ida-e-volta-por-timestamptz
  (let [inst (Instant/parse "2026-06-26T12:34:56.123456Z")]
    (jdbc/with-transaction [tx *ds*]
      (jdbc/execute-one! tx ["CREATE TEMP TABLE _t (ts timestamptz) ON COMMIT DROP"])
      (jdbc/execute-one! tx ["INSERT INTO _t (ts) VALUES (?)" inst])
      (let [lido (:_t/ts (jdbc/execute-one! tx ["SELECT ts FROM _t"]))]
        (is (instance? Instant lido) "timestamptz volta como java.time.Instant (nao java.sql.Timestamp)")
        (is (= inst lido) "round-trip preserva o instante exato (precisao de microssegundo)")))))
