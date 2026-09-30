(ns oplenario.integracao-ia.diplomat.consumers
  "Inbound do outbox (§22.10 diplomat/consumers, ADR-0008): o `integracao_ia` consome os eventos de dominio que
  a lista de promocoes preve e os publica no feed da IA. Nome de consumidor proprio = dedup independente. Os
  tipos sao STRINGS (contrato de fiacao do bus), vindos da mesma fonte que decide a promocao."
  (:require [oplenario.integracao-ia.components.repositorio :as repo]
            [oplenario.integracao-ia.logic :as logic]
            [oplenario.kernel.outbox :as outbox]))

(def ^:private nome-consumidor "integracao-ia-promocao")

(def ^:private nome-consumidor-cota "integracao-ia-cota-da-casa")

(def tipo-casa-suspensa "admin_sistema.casa.suspensa")
(def tipo-casa-reativada "admin_sistema.casa.reativada")

(defn registrar [registro]
  (-> (reduce (fn [r tipo] (outbox/registrar r nome-consumidor tipo repo/promover-em-tx!))
              registro (sort (keys logic/promocoes)))
      ;; ADR-0018: a Casa suspensa tem a cota de IA zerada; reativada, volta ao que valia (tipos STRING, sem importar
      ;; admin_sistema)
      (outbox/registrar nome-consumidor-cota tipo-casa-suspensa repo/pausar-por-suspensao-em-tx!)
      (outbox/registrar nome-consumidor-cota tipo-casa-reativada repo/retomar-apos-reativacao-em-tx!)))
