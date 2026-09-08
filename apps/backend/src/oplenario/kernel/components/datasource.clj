(ns oplenario.kernel.components.datasource
  "Component do pool de conexoes (HikariCP): start abre, stop fecha. O datasource injetado e' o
  que o db/ de cada modulo recebe (next.jdbc/HoneySQL) — sempre schema-qualified (§22.10: nunca
  search_path global, que vazaria entre modulos no pool compartilhado)."
  (:require [com.stuartsierra.component :as component]
            ;; carrega as extensoes Instant<->timestamptz no processo (data layer, carry F0.3).
            [oplenario.kernel.db-tipos])
  (:import (com.zaxxer.hikari HikariConfig HikariDataSource)))

(set! *warn-on-reflection* true)

(defn- config-base
  "HikariConfig comum aos dois pools. `leak-ms` = 0 desliga a deteccao de vazamento."
  ^HikariConfig [{:keys [jdbc-url user password]} nome tamanho leak-ms]
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
    ;; guard pelos DOIS campos: `ds` preenchido com `ds-lock` nil e' meio-iniciado, e o relay que
    ;; recebe `ds-lock` nil entra em laco de erro logando "conexao de lideranca caiu" — uma causa que
    ;; e' mentira. O invariante e' os dois juntos, entao o guard tem de ser sobre os dois.
    (if (and ds ds-lock)
      this
      (let [{:keys [pool-max-size] :as db} (:db config)
            ;; pool de TRABALHO: deteccao de vazamento LIGADA — e' aqui que uma tx que nao fecha tem
            ;; de aparecer. Orcamento de conexoes por processo = pool-max-size + 1 (o pool de lock).
            trabalho (HikariDataSource. (config-base db "oplenario" (or pool-max-size 10) 30000))]
        ;; o construtor do Hikari e' ANSIOSO (checkFailFast abre e valida uma conexao). Se o segundo
        ;; pool estourar — PG batendo max_connections, banco caindo entre as duas chamadas — o primeiro
        ;; ficaria orfao: threads e conexoes vivas sem ninguem que possa fecha-las, porque o `this` que
        ;; sobrevive tem `:ds` nil. Na JVM de producao o processo morre junto; na suite e no REPL, nao.
        (let [lock (try
                     ;; pool de LOCK (dedicado, 1 conexao): o advisory lock de lideranca do relay e'
                     ;; session-level, entao a conexao fica presa DE PROPOSITO enquanto o relay vive
                     ;; (outbox_relay.clj ja' previa este pool). Saindo do pool principal, o detector de
                     ;; vazamento a denunciava em todo boot — falso-positivo garantido, que treina o
                     ;; operador a ignorar a categoria e entao esconde um vazamento real. Aqui o detector
                     ;; fica DESLIGADO porque segurar a conexao e' o desenho, nao o defeito.
                     (HikariDataSource. (config-base db "oplenario-lock" 1 0))
                     (catch Throwable e
                       (.close trabalho)
                       (throw e)))]
          (assoc this :ds trabalho :ds-lock lock)))))
  (stop [this]
    (when ds (.close ^HikariDataSource ds))
    (when ds-lock (.close ^HikariDataSource ds-lock))
    (assoc this :ds nil :ds-lock nil)))

(defn datasource
  "Cria o Component do pool a partir do config (mapa com :db{:jdbc-url :user :password :pool-max-size})."
  [config]
  (map->Datasource {:config config}))
