(ns oplenario.paineis.adapters.out.pendencia
  "Gate de SAIDA `models -> wire/out` das pendencias (§22.10 adapters/out, ADR-0001) — chamado SO pelo
  diplomat/. Projeta o read-model do dominio (pendencias abertas, kebab) p/ a representacao externa (strings)
  e FILTRA o tenant (ente-id), que nunca vaza. Validada contra wire/out.OQueVenceOut (drift de campo = bug
  de servidor -> 500, nunca resposta malformada)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.paineis.wire.out.pendencia :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- pendencia->wire [p]
  {:objeto-tipo (:objeto-tipo p) :objeto-id (->str (:objeto-id p)) :protocolo (:protocolo p)
   :vence-em (->str (:vence-em p)) :estado (:estado p)})

(defn o-que-vence->wire
  "Read-model do painel {:pendencias [...]} -> OQueVenceOut (validada)."
  [pendencias]
  (let [out {:pendencias (mapv pendencia->wire pendencias)}]
    (when-not (m/validate wire/OQueVenceOut out)
      (throw (ex-info "projecao do painel de pendencias viola o contrato OQueVenceOut (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/OQueVenceOut out))})))
    out))
