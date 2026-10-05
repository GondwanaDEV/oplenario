(ns oplenario.legislativo.adapters.out.norma
  "Gate de SAIDA `models -> wire/out` da NORMA (§22.10 adapters/out, ADR-0001, F3.8b). `norma->wire`
  projeta+valida (mesmo padrao `validado` de adapters.out.autografo)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.legislativo.wire.out.norma :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- validado [schema out tipo]
  (when-not (m/validate schema out)
    (throw (ex-info (str "projecao de " tipo " viola o contrato wire/out (bug de servidor)")
                    {:campos (keys (me/humanize (m/explain schema out)))})))
  out)

(defn norma->wire
  "norma (dominio, kebab) -> NormaOut."
  [{:keys [id proposicao-id tipo-norma numero ano urn ementa estado promulgado-em publicado-em
           veiculo-publicacao lock-version]}]
  (validado wire/NormaOut
            {:id (->str id) :proposicao-id (->str proposicao-id) :tipo-norma tipo-norma :numero numero
             :ano ano :urn urn :ementa ementa :estado estado :promulgado-em (->str promulgado-em)
             :publicado-em (->str publicado-em) :veiculo-publicacao veiculo-publicacao
             :lock-version lock-version}
            "norma"))
