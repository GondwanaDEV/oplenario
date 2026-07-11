(ns oplenario.legislativo.adapters.out.tramitacao-executiva
  "Gate de SAIDA `models -> wire/out` da TRAMITACAO NO EXECUTIVO (§22.10 adapters/out, ADR-0001, Onda B
  Slice 7, F3.8a). `tramitacao-executiva->wire` projeta+valida (mesmo padrao `validado` dos adapters/out
  irmaos)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.legislativo.wire.out.tramitacao-executiva :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- validado [schema out tipo]
  (when-not (m/validate schema out)
    (throw (ex-info (str "projecao de " tipo " viola o contrato wire/out (bug de servidor)")
                    {:campos (keys (me/humanize (m/explain schema out)))})))
  out)

(defn tramitacao-executiva->wire
  "tramitacao executiva (dominio, kebab) -> TramitacaoExecutivaOut."
  [{:keys [id autografo-id estado veto-tipo veto-razoes veto-votacao-id respondido-em apreciado-em
           lock-version]}]
  (validado wire/TramitacaoExecutivaOut
            {:id (->str id) :autografo-id (->str autografo-id) :estado estado :veto-tipo veto-tipo
             :veto-razoes veto-razoes :veto-votacao-id (->str veto-votacao-id)
             :respondido-em (->str respondido-em) :apreciado-em (->str apreciado-em)
             :lock-version lock-version}
            "tramitacao executiva"))
