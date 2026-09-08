(ns oplenario.kernel.components.outbox-relay-test
  "Integracao (PG real): a conexao de LIDERANCA do relay.

  `ciclo-lider` (outbox_relay.clj) mantem UMA conexao aberta pela vida inteira do relay — o advisory
  lock e' session-level, e' assim que a lideranca se sustenta. Enquanto essa conexao saia do pool
  PRINCIPAL, o `leakDetectionThreshold` de 30s do Hikari (datasource.clj) a denunciava como vazada em
  TODO boot, 30s depois de subir:

      WARN  [oplenario housekeeper] ProxyLeakTask - Connection leak detection triggered for
            PgConnection@... on thread oplenario-outbox-relay, stack trace follows
      java.lang.Exception: Apparent connection leak detected
        at ...outbox_relay$ciclo_lider.invokeStatic(outbox_relay.clj:30)

  Falso-positivo estrutural: ruido garantido que treina o operador a ignorar a categoria inteira, e
  entao esconde um vazamento de verdade. O conserto e' o pool de lock DEDICADO que o proprio docstring
  do relay ja' previa. Este teste REPROVA se a lideranca voltar a sair do pool principal."
  (:require [clojure.test :refer [deftest is]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as ds]
            [oplenario.kernel.components.outbox-relay :as relay])
  (:import (com.zaxxer.hikari HikariDataSource)))

(set! *warn-on-reflection* true)

(defn- ativas ^long [^HikariDataSource pool]
  (.getActiveConnections (.getHikariPoolMXBean pool)))

(deftest a-lideranca-nao-prende-conexao-do-pool-principal
  (let [d (component/start (ds/datasource (config/carregar)))
        r (component/start (assoc (relay/relay {:registro {} :intervalo-ms 100}) :datasource d))]
    (try
      ;; o relay abre a conexao de lock no 1o instante de `ciclo-lider`, ANTES de tentar a lideranca —
      ;; a assercao independe de este processo ganhar o lock (o app da stack pode estar segurando).
      (Thread/sleep 1500)
      (is (some? (:ds-lock d))
          "o datasource expoe um pool DEDICADO para a conexao de lideranca")
      (is (not (identical? (:ds d) (:ds-lock d)))
          "o pool de lock e' um pool distinto, nao um alias do principal")
      (is (zero? (.getLeakDetectionThreshold (.getHikariConfigMXBean ^HikariDataSource (:ds-lock d))))
          "no pool de lock a deteccao de vazamento fica DESLIGADA — segurar a conexao ali e' o desenho")
      (is (pos? (.getLeakDetectionThreshold (.getHikariConfigMXBean ^HikariDataSource (:ds d))))
          "no pool principal a deteccao CONTINUA ligada — o conserto nao pode ser desligar o detector")
      (is (= 1 (ativas (:ds-lock d)))
          "a conexao de lideranca sai do pool de lock")
      (finally (component/stop r) (component/stop d)))))
