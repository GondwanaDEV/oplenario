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
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as ds]
            [oplenario.kernel.components.outbox-relay :as relay]
            [oplenario.kernel.components.scheduler :as scheduler])
  (:import (com.zaxxer.hikari HikariDataSource)))

(set! *warn-on-reflection* true)

(def ^:private chave-lock-relay
  "Mesma chave de `outbox_relay/chave-lock-relay` (privada la'). O teste a toma ANTES de subir o relay
  para que o relay NUNCA vire lider — ver o comentario no proprio teste: nao e' detalhe de isolamento,
  e' o que impede o teste de destruir o outbox do banco compartilhado."
  911)

(defn- ativas ^long [^HikariDataSource pool]
  (.getActiveConnections (.getHikariPoolMXBean pool)))

(defn- esperar
  "Poll com prazo, em vez de `Thread/sleep` fixo. Sob a contencao de CPU/memoria desta maquina (ver
  `docs/16-ledger-prontidao.md`, Fase 7), init de pool + start de thread + primeiro borrow passa de
  1,5s com folga, e um sleep fixo vira vermelho falso."
  [ms-limite f]
  (let [prazo (+ (System/currentTimeMillis) (long ms-limite))]
    (loop []
      (or (f)
          (when (< (System/currentTimeMillis) prazo)
            (Thread/sleep 50)
            (recur))))))

(defn- conexao-crua
  "Conexao direta (DriverManager), FORA dos dois pools. O `guarda` da lideranca nao pode sair do pool de
  lock — ele tem tamanho 1, e tomar a unica conexao faria o relay bloquear ate o timeout — nem do pool
  principal, cuja ocupacao este teste afirma ser zero."
  ^java.sql.Connection [config]
  (let [{:keys [jdbc-url user password]} (:db config)]
    (jdbc/get-connection (jdbc/get-datasource {:jdbcUrl jdbc-url :user user :password password}))))

(deftest a-lideranca-nao-prende-conexao-do-pool-principal
  (let [cfg (config/carregar)
        d   (component/start (ds/datasource cfg))]
    ;; TOMA A LIDERANCA ANTES DE SUBIR O RELAY. Sem isto o relay deste teste pode ganhar o lock 911 e
    ;; rodar `outbox/drenar!` com registro VAZIO — e `drenar-um!` marca `processed_at` mesmo sem
    ;; consumidor, ou seja, DESCARTA em silencio todo evento pendente do banco compartilhado. E o
    ;; procedimento canonico de suite deste projeto manda parar o `oplenario-app-1` antes do run,
    ;; justamente para liberar esse lock — no run oficial, este teste seria o lider mais provavel.
    ;; A assercao nao depende de quem e' lider; o EFEITO COLATERAL depende.
    (with-open [guarda (conexao-crua cfg)]
      (is (true? (scheduler/tentar-lider? guarda chave-lock-relay))
          "pre-condicao: o teste segura a lideranca, entao o relay nunca drena nada")
      (let [r (component/start (assoc (relay/relay {:registro {} :intervalo-ms 100}) :datasource d))]
        (try
          ;; a conexao de lock e' o PRIMEIRO ato de `ciclo-lider`, antes de tentar a lideranca: o relay
          ;; a toma mesmo perdendo o lock para o `guarda` acima (que e' conexao crua, fora dos pools).
          (esperar 15000 #(= 1 (ativas (:ds-lock d))))
          (is (some? (:ds-lock d))
              "o datasource expoe um pool DEDICADO para a conexao de lideranca")
          (is (not (identical? (:ds d) (:ds-lock d)))
              "o pool de lock e' um pool distinto, nao um alias do principal")
          (is (zero? (.getLeakDetectionThreshold (.getHikariConfigMXBean ^HikariDataSource (:ds-lock d))))
              "no pool de lock a deteccao de vazamento fica DESLIGADA — segurar a conexao ali e' o desenho")
          (is (pos? (.getLeakDetectionThreshold (.getHikariConfigMXBean ^HikariDataSource (:ds d))))
              "no pool principal a deteccao CONTINUA ligada — o conserto nao pode ser desligar o detector")
          (is (= 1 (ativas (:ds-lock d)))
              "a conexao de lideranca do relay sai do pool de lock")
          ;; a assercao que o NOME do teste promete, e que faltava: o detector so' enxergava a forma que
          ;; conhecia. Com ela, o defeito reprova pelos dois lados — a de lock ausente E a de trabalho presa.
          (is (zero? (ativas (:ds d)))
              "NENHUMA conexao do pool principal fica presa — e' o que o nome deste teste afirma")
          (finally (component/stop r)))))
    (component/stop d)))

(deftest relay-sem-pool-de-lock-falha-nomeando-o-defeito
  ;; `(jdbc/get-connection nil)` estoura dentro de `ciclo-lider`, `loop-relay` engole como Throwable e
  ;; loga "conexao de lideranca caiu — reconectando" em laco: uma causa FALSA, para sempre, sem drenar.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"sem :ds-lock"
        (component/start (assoc (relay/relay {:registro {}}) :datasource {:ds :qualquer-coisa})))
      "datasource meio-iniciado tem de falhar no start, nomeando o defeito"))
