(ns oplenario.participacao.adapters.in.prorrogar-ouvidoria
  "Gate de ENTRADA `wire/in -> models` da PRORROGACAO de ouvidoria (§22.10 adapters/in, ADR-0001) —
  chamado SO pelo diplomat/. Coage o corpo {justificativa} da rota de SERVIDOR (prorrogar prazo), fail-closed
  (-> 400). ALLOWLIST estrita (so `justificativa`): as datas sao CALCULADAS no controller, nunca vem do corpo."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.participacao.wire.in.prorrogar-ouvidoria :as wire]))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- so-esperados [m campos]
  (reduce (fn [acc k] (cond-> acc (contains? m k) (assoc (keyword k) (get m k)))) {} campos))

(def ^:private campos ["justificativa"])

(defn coagir-prorrogar
  "Corpo JSON {justificativa} (chaves STRING) -> mapa de dominio {:justificativa}. ALLOWLIST descarta campo
  forjado (de_data/para_data — as datas sao SEMPRE calculadas no controller, nunca aceitas do cliente)."
  [json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON {justificativa}" {:campo :corpo}))
  (let [mp (so-esperados json-params campos)]
    (when-let [erros (m/explain wire/ProrrogarOuvidoriaIn mp)]
      (invalido! "corpo de prorrogacao de ouvidoria invalido" {:campos (keys (me/humanize erros))}))
    (when (str/blank? (:justificativa mp)) (invalido! "justificativa obrigatoria nao pode ser vazia" {:campo :justificativa}))
    {:justificativa (:justificativa mp)}))
