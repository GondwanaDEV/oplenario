(ns oplenario.admin-sistema.diplomat.producers
  "Outbound: os eventos do ciclo de vida da Casa no outbox, na MESMA tx da transicao (ADR-0018). O pool do
  `admin_sistema` herda o INSERT em `shared.outbox` (mig 0009); o relay drena cross-tenant."
  (:require [oplenario.admin-sistema.events.ente :as ev]
            [oplenario.kernel.eventos :as eventos]
            [oplenario.kernel.outbox :as outbox]))

(def ^:private bus (outbox/bus))

(defn emitir-suspensa! [tx ente-id payload] (eventos/emitir! bus tx (ev/suspensa ente-id payload)))
(defn emitir-reativada! [tx ente-id payload] (eventos/emitir! bus tx (ev/reativada ente-id payload)))
(defn emitir-encerrada! [tx ente-id payload] (eventos/emitir! bus tx (ev/encerrada ente-id payload)))
