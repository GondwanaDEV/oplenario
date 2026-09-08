(ns oplenario.kernel.components.outbox-relay
  "Relay do bus (§22.9 E3): Component que, como LIDER unico (advisory lock via scheduler), varre o
  shared.outbox periodicamente e despacha aos consumidores (outbox/drenar!), e poda o ledger de inbox.
  Thread daemon; AUTO-CURA (reabre a conexao de lideranca se ela cair) e para limpo no stop.
  CUSTO: mantem UMA conexao presa p/ o advisory lock enquanto vive (lock e' session-level). Ela sai do
  pool DEDICADO `:ds-lock` (datasource.clj), nunca do pool de trabalho — no pool de trabalho o
  `leakDetectionThreshold` a denunciava como vazada em todo boot, ruido garantido que esconderia um
  vazamento de verdade. Dimensionar :db :pool-max-size contra a concorrencia normal apenas."
  (:require [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [clojure.tools.logging :as log]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.components.scheduler :as scheduler]))

(set! *warn-on-reflection* true)

(def ^:private chave-lock-relay 911)
(def ^:private retencao-dias 30)
(def ^:private intervalo-limpeza-ms (* 60 60 1000)) ; poda o ledger a cada ~1h

(defn- dormir
  "Sleep interrompivel: stop interrompe a thread -> encerra `rodando?` p/ saida limpa e pronta."
  [ms rodando?]
  (try (Thread/sleep (long ms))
       (catch InterruptedException _ (reset! rodando? false))))

(defn- ciclo-lider
  "Um ciclo com conexao de lock propria. Vira lider UMA vez (sem re-adquirir -> sem contador
  re-entrante); enquanto lider, drena e poda (time-gated). Sai (lanca) ao cair a conexao.

  `ds-lock` e `ds` sao pools DISTINTOS de proposito: a conexao de lideranca fica presa enquanto o
  relay vive (advisory lock session-level), e no pool de trabalho isso disparava o detector de
  vazamento do Hikari em todo boot — ver `datasource.clj`."
  [ds ds-lock registro rodando? intervalo-ms]
  (with-open [lock-conn (jdbc/get-connection ds-lock)]
    (loop [lider? false, ultima-limpeza 0]
      (when @rodando?
        (let [lider?  (or lider? (scheduler/tentar-lider? lock-conn chave-lock-relay))
              agora   (System/currentTimeMillis)
              limpar? (and lider? (> (- agora ultima-limpeza) intervalo-limpeza-ms))]
          (when lider?
            (try (outbox/drenar! ds registro)
                 (catch Exception e
                   (log/error e "relay: falha ao drenar o outbox — retenta no proximo tick")))
            (when limpar?
              (try (let [n (outbox/limpar-consumidos! ds retencao-dias)]
                     (when (pos? (long (or n 0)))
                       (log/info "relay: podou" n "entradas antigas do ledger de inbox")))
                   (catch Exception e
                     (log/warn e "relay: falha ao podar o ledger de inbox")))))
          (dormir intervalo-ms rodando?)
          (recur lider? (if limpar? agora ultima-limpeza)))))))

(defn- loop-relay
  "Loop externo de auto-cura: se a conexao de lideranca cair (PG restart/blip), loga e reabre."
  [ds ds-lock registro rodando? intervalo-ms]
  (while @rodando?
    (try (ciclo-lider ds ds-lock registro rodando? intervalo-ms)
         (catch Throwable e
           (when @rodando?
             (log/error e "relay: conexao de lideranca caiu — reconectando")
             (dormir intervalo-ms rodando?))))))

(defrecord OutboxRelay [datasource registro intervalo-ms rodando? worker]
  component/Lifecycle
  (start [this]
    (if worker
      this
      (let [run?      (atom true)
            intervalo (if (nil? intervalo-ms) 1000 intervalo-ms)
            w         (doto (Thread. ^Runnable #(loop-relay (:ds datasource) (:ds-lock datasource) registro run? intervalo)
                                     "oplenario-outbox-relay")
                        (.setDaemon true)
                        (.start))]
        (assoc this :rodando? run? :worker w))))
  (stop [this]
    (when rodando? (reset! rodando? false))
    (when worker
      (.interrupt ^Thread worker)
      (.join ^Thread worker 3000)
      (when (.isAlive ^Thread worker)
        (log/warn "relay: thread ainda viva apos stop (daemon — morre com a JVM)")))
    (assoc this :rodando? nil :worker nil)))

(defn relay
  "Component do relay. Recebe :registro (mapa de consumidores) + :intervalo-ms (default 1000);
  depende de :datasource (kernel) injetado via `using`. Sobe como lider unico e drena o outbox."
  [{:keys [registro intervalo-ms]}]
  (map->OutboxRelay {:registro registro :intervalo-ms intervalo-ms}))
