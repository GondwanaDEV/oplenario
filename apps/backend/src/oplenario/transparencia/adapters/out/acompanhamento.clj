(ns oplenario.transparencia.adapters.out.acompanhamento
  "Gate de SAIDA `models -> wire/out` do acompanhamento (§22.10 adapters/out, ADR-0001) — chamado SO pelo
  diplomat/. `recibo->wire` projeta o estado da subscricao; `minha->wire` projeta o item da lista 'minhas
  materias'. Validada contra wire/out (drift = bug de servidor -> 500)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.transparencia.wire.out.acompanhamento :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- validar! [schema out rotulo]
  (when-not (m/validate schema out)
    (throw (ex-info (str "projecao viola o contrato " rotulo " (bug de servidor)")
                    {:erros (me/humanize (m/explain schema out))})))
  out)

(defn recibo->wire
  "{:estado ...} -> ReciboOut."
  [estado]
  (validar! wire/ReciboOut {:estado estado} "ReciboOut"))

(defn minha->wire
  "Item da lista (materia + seguido-em) -> MinhaMateriaOut."
  [m]
  (validar! wire/MinhaMateriaOut
            {:proposicao-id (->str (:proposicao-id m)) :tipo (:tipo m) :ano (:ano m)
             :sequencial (:sequencial m) :urn-lex (:urn-lex m) :ementa (:ementa m) :estado (:estado m)
             :seguido-em (->str (:seguido-em m))}
            "MinhaMateriaOut"))

(defn minhas->wire
  "A lista inteira, item a item."
  [ms]
  (mapv minha->wire ms))
