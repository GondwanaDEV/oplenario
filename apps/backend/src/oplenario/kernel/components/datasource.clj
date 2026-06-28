(ns oplenario.kernel.components.datasource
  "Component do pool de conexoes (HikariCP): start abre, stop fecha. O datasource injetado e' o
  que o db/ de cada modulo recebe (next.jdbc/HoneySQL) — sempre schema-qualified (§22.10: nunca
  search_path global, que vazaria entre modulos no pool compartilhado)."
  (:require [com.stuartsierra.component :as component]
            ;; carrega as extensoes Instant<->timestamptz no processo (data layer, carry F0.3).
            [oplenario.kernel.db-tipos])
  (:import (com.zaxxer.hikari HikariConfig HikariDataSource)))

(set! *warn-on-reflection* true)

(defrecord Datasource [config ds]
  component/Lifecycle
  (start [this]
    (if ds
      this
      (let [{:keys [jdbc-url user password pool-max-size]} (:db config)
            hc (doto (HikariConfig.)
                 (.setJdbcUrl jdbc-url)
                 (.setUsername user)
                 (.setPassword password)
                 (.setPoolName "oplenario")
                 ;; pool explicito: o operador dimensiona max_connections do PG contra (pool x replicas).
                 (.setMaximumPoolSize (int (or pool-max-size 10)))
                 (.setAutoCommit true)                ; next.jdbc usa tx explicita (with-transaction)
                 (.setKeepaliveTime 60000)            ; mantem conexao viva sob NAT/firewall cloud (idle TCP)
                 (.setLeakDetectionThreshold 30000))] ; loga o borrow-site de conexao nao devolvida em 30s
        (assoc this :ds (HikariDataSource. hc)))))
  (stop [this]
    (when ds (.close ^HikariDataSource ds))
    (assoc this :ds nil)))

(defn datasource
  "Cria o Component do pool a partir do config (mapa com :db{:jdbc-url :user :password :pool-max-size})."
  [config]
  (map->Datasource {:config config}))
