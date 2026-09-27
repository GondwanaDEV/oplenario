(ns oplenario.normas.adapters.out.versao
  "dominio -> wire das normas de referencia (§22.10 adapters/out)."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn- s [x] (some-> x str))

(defn norma->wire [n]
  {:id (str (:id n)) :camada (:camada n) :especie (:especie n) :titulo (:titulo n) :numero (:numero n)
   :data (s (:data n)) :da-casa (some? (:ente-id n))})

(defn versao-resumo->wire [v]
  (when v
    {:id (str (:id v)) :estado (:estado v) :consolidada-ate (s (:consolidada-ate v)) :fonte (:fonte v)
     :n-dispositivos (:n-dispositivos v) :n-alertas (count (:alertas v))
     :enviada-em (s (:enviada-em v)) :decidida-em (s (:decidida-em v))}))

(defn lista->wire [normas]
  {:normas (mapv (fn [n] {:norma (norma->wire n) :vigente (versao-resumo->wire (:vigente n))
                          :em-conferencia (versao-resumo->wire (:em-conferencia n))})
                 normas)})

(defn versao->wire [v]
  {:norma (norma->wire (:norma v))
   :versao (versao-resumo->wire v)
   :alertas (mapv str (:alertas v))
   :dispositivos (mapv (fn [d] (-> (select-keys d [:endereco :rotulo :tipo :pai :ordem :texto :agrupador])
                                   (update :texto #(if (str/blank? %) "" %))))
                       (:dispositivos v))})
