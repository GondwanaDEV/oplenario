(ns oplenario.sessoes.adapters.out.incidente
  "Gate de SAIDA `models -> wire/out` dos INCIDENTES PROCESSUAIS (§16.13, §22.10 adapters/out). Projeta o recibo
  do registro p/ a borda. Validado contra o contrato wire/out (drift de campo = bug de servidor -> 500, nunca
  resposta malformada que envenena o codegen do front, Eixo 8)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.sessoes.wire.out :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn recibo-incidente->wire
  "Recibo de dominio {:id uuid} -> IncidenteReciboOut (validado, resposta 201). O `id` do incidente gravado."
  [{:keys [id]}]
  (let [out {:id (->str id)}]
    (when-not (m/validate wire/IncidenteReciboOut out)
      (throw (ex-info "recibo de incidente viola o contrato IncidenteReciboOut (bug de servidor)"
                      {:campos (keys (me/humanize (m/explain wire/IncidenteReciboOut out)))})))
    out))
