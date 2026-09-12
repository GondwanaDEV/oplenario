(ns oplenario.kernel.outbox-test
  "Integracao do bus (§22.9 E3 / E2): producer grava no shared.outbox na tx; relay drena via
  SELECT FOR UPDATE SKIP LOCKED e despacha aos consumidores; dedup por (consumidor, idempotency_key)
  no ledger de inbox. Postgres real (host:5544 via DATABASE_URL)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.eventos :as eventos]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.components.scheduler :as scheduler]
            [oplenario.kernel.components.outbox-relay :as outbox-relay]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)]
        (try (t) (finally (component/stop c)))))))

(use-fixtures :each
  (fn [t]
    (jdbc/execute! *ds* ["TRUNCATE shared.outbox, shared.evento_consumido"])
    (t)))

(deftest emitir-grava-evento-pendente-no-outbox
  (let [bus (outbox/bus)
        ev  (eventos/evento "kernel.teste.evento-qualquer" (random-uuid) {:numero 7})]
    (jdbc/with-transaction [tx *ds*]
      (eventos/emitir! bus tx ev))
    (let [rows (jdbc/execute! *ds* ["SELECT tipo, idempotency_key, processed_at FROM shared.outbox"])]
      (is (= 1 (count rows)) "uma linha no outbox")
      (is (= "kernel.teste.evento-qualquer" (:outbox/tipo (first rows))) "tipo gravado")
      (is (nil? (:outbox/processed_at (first rows))) "nasce pendente (processed_at NULL)"))))

(deftest emitir-respeita-a-atomicidade-da-tx
  (let [bus (outbox/bus)
        ev  (eventos/evento "x" (random-uuid) {})]
    (try
      (jdbc/with-transaction [tx *ds*]
        (eventos/emitir! bus tx ev)
        (throw (ex-info "rollback proposital" {})))
      (catch clojure.lang.ExceptionInfo _ nil))
    (is (= 0 (count (jdbc/execute! *ds* ["SELECT 1 FROM shared.outbox"])))
        "tx revertida => evento nao fica no outbox (atomicidade outbox-com-o-ato)")))

(deftest relay-entrega-uma-vez-e-marca-processado
  (let [bus       (outbox/bus)
        recebidos (atom [])
        registro  (outbox/registrar {} "busca" "kernel.teste.evento-qualquer"
                                    (fn [_tx ev] (swap! recebidos conj ev)))
        ev        (eventos/evento "kernel.teste.evento-qualquer" (random-uuid) {:numero 7})]
    (jdbc/with-transaction [tx *ds*] (eventos/emitir! bus tx ev))
    (is (= 1 (outbox/drenar! *ds* registro)) "drena 1 evento")
    (is (= 1 (count @recebidos)) "consumidor recebeu uma vez")
    (is (= {:numero 7} (:payload (first @recebidos))) "payload chega desserializado (jsonb->mapa)")
    (is (= 0 (outbox/drenar! *ds* registro)) "nada pendente na 2a passada")
    (is (= 1 (count @recebidos)) "sem reentrega — processed_at marca o consumido")))

(deftest dedup-no-consumidor-por-idempotency-key
  ;; duas linhas com a MESMA idempotency_key (emit retried c/ chave estavel, ou reentrega) -> handler UMA vez.
  (let [chamadas (atom 0)
        registro (outbox/registrar {} "busca" "x" (fn [_tx _ev] (swap! chamadas inc)))
        ik       "chave-estavel-123"]
    (dotimes [_ 2]
      (jdbc/execute-one! *ds* ["INSERT INTO shared.outbox (ente_id, tipo, payload, idempotency_key)
                                VALUES (?, ?, ?::jsonb, ?)" (random-uuid) "x" "{}" ik]))
    (outbox/drenar! *ds* registro)
    (is (= 1 @chamadas) "mesma idempotency_key -> handler chamado UMA vez (dedup §22.9 E2)")
    (is (= 1 (count (jdbc/execute! *ds* ["SELECT 1 FROM shared.evento_consumido WHERE consumidor = ?" "busca"])))
        "uma entrada no ledger de inbox")))

(deftest fan-out-broadcast-a-multiplos-consumidores
  (let [bus      (outbox/bus)
        a        (atom 0)
        b        (atom 0)
        registro (-> {}
                     (outbox/registrar "busca" "ato.publicado" (fn [_ _] (swap! a inc)))
                     (outbox/registrar "notificacao" "ato.publicado" (fn [_ _] (swap! b inc))))
        ev       (eventos/evento "ato.publicado" (random-uuid) {})]
    (jdbc/with-transaction [tx *ds*] (eventos/emitir! bus tx ev))
    (outbox/drenar! *ds* registro)
    (is (= 1 @a) "consumidor A recebeu")
    (is (= 1 @b) "consumidor B recebeu (broadcast por tipo)")))

(deftest lideranca-por-advisory-lock-e-exclusiva
  ;; so um replica do relay varre: pg_try_advisory_lock e' session-level (preso a' conexao).
  (with-open [c1 (jdbc/get-connection *ds*)
              c2 (jdbc/get-connection *ds*)]
    (is (true?  (scheduler/tentar-lider? c1 4242)) "1a conexao adquire a lideranca")
    (is (false? (scheduler/tentar-lider? c2 4242)) "2a nao adquire (lock detido na sessao c1)")
    (scheduler/liberar-lider! c1 4242)
    (is (true?  (scheduler/tentar-lider? c2 4242)) "apos liberar, a 2a adquire")))

(deftest relay-component-drena-em-background
  (let [recebidos (atom [])
        registro  (outbox/registrar {} "busca" "x" (fn [_tx ev] (swap! recebidos conj ev)))
        ds-comp   (component/start (datasource/datasource (config/carregar)))
        relay     (component/start (assoc (outbox-relay/relay {:registro registro :intervalo-ms 50})
                                          :datasource ds-comp))]
    (try
      (jdbc/with-transaction [tx (:ds ds-comp)]
        (eventos/emitir! (outbox/bus) tx (eventos/evento "x" (random-uuid) {:n 1})))
      ;; espera (poll) o relay em background drenar — sem sleep fixo flaky
      (loop [i 0] (when (and (empty? @recebidos) (< i 60)) (Thread/sleep 50) (recur (inc i))))
      (is (= 1 (count @recebidos)) "o relay em background (lider) drenou o evento emitido")
      (finally
        (component/stop relay)
        (component/stop ds-comp)))))

(deftest limpar-consumidos-poda-entradas-antigas
  (jdbc/execute-one! *ds* ["INSERT INTO shared.evento_consumido (consumidor, idempotency_key, processado_em)
                            VALUES (?, ?, now() - interval '40 days')" "busca" "antiga"])
  (jdbc/execute-one! *ds* ["INSERT INTO shared.evento_consumido (consumidor, idempotency_key)
                            VALUES (?, ?)" "busca" "recente"])
  (is (= 1 (outbox/limpar-consumidos! *ds* 30)) "remove a entrada com >30 dias")
  (is (= 1 (count (jdbc/execute! *ds* ["SELECT 1 FROM shared.evento_consumido"]))) "mantem a recente"))
