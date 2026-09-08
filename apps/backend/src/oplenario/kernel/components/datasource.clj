(ns oplenario.kernel.components.datasource
  "Component do pool de conexoes (HikariCP): start abre, stop fecha. O datasource injetado e' o
  que o db/ de cada modulo recebe (next.jdbc/HoneySQL) — sempre schema-qualified (§22.10: nunca
  search_path global, que vazaria entre modulos no pool compartilhado)."
  (:require [com.stuartsierra.component :as component]
            ;; carrega as extensoes Instant<->timestamptz no processo (data layer, carry F0.3).
            [oplenario.kernel.db-tipos])
  (:import (com.zaxxer.hikari HikariConfig HikariDataSource)))

(set! *warn-on-reflection* true)

(defn- ^HikariConfig config-base
  "HikariConfig comum aos dois pools. `leak-ms` = 0 desliga a deteccao de vazamento."
  [{:keys [jdbc-url user password]} nome tamanho leak-ms]
  (doto (HikariConfig.)
    (.setJdbcUrl jdbc-url)
    (.setUsername user)
    (.setPassword password)
    (.setPoolName nome)
    (.setMaximumPoolSize (int tamanho))
    (.setAutoCommit true)                     ; next.jdbc usa tx explicita (with-transaction)
    (.setKeepaliveTime 60000)                 ; mantem conexao viva sob NAT/firewall cloud (idle TCP)
    (.setLeakDetectionThreshold (long leak-ms))))

(defrecord Datasource [config ds ds-lock]
  component/Lifecycle
  (start [this]
    (if ds
      this
      (let [{:keys [pool-max-size] :as db} (:db config)]
        (assoc this
               ;; pool de TRABALHO: o operador dimensiona max_connections do PG contra (pool x replicas).
               ;; Deteccao de vazamento LIGADA — e' aqui que uma tx que nao fecha tem de aparecer.
               :ds (HikariDataSource. (config-base db "oplenario" (or pool-max-size 10) 30000))
               ;; pool de LOCK (dedicado, 1 conexao): o advisory lock de lideranca do relay e'
               ;; session-level, entao a conexao fica presa DE PROPOSITO enquanto o relay vive
               ;; (outbox_relay.clj:5 ja' previa este pool). Saindo do pool principal, o detector de
               ;; vazamento a denunciava em todo boot — falso-positivo garantido, que treina o operador
               ;; a ignorar a categoria e entao esconde um vazamento real. Aqui o detector fica
               ;; DESLIGADO porque segurar a conexao e' o desenho, nao o defeito.
               :ds-lock (HikariDataSource. (config-base db "oplenario-lock" 1 0))))))
  (stop [this]
    (when ds (.close ^HikariDataSource ds))
    (when ds-lock (.close ^HikariDataSource ds-lock))
    (assoc this :ds nil :ds-lock nil)))

(defn datasource
  "Cria o Component do pool a partir do config (mapa com :db{:jdbc-url :user :password :pool-max-size})."
  [config]
  (map->Datasource {:config config}))
