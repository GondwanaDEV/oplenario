(ns oplenario.participacao.adapters.in.resposta-titular
  "Gate de ENTRADA `wire/in -> models` da RESPOSTA a uma solicitacao do titular (§22.10 adapters/in, ADR-0001) —
  chamado SO pelo diplomat/. Coage o corpo {corpo} da rota de SERVIDOR, fail-closed (-> 400). ALLOWLIST estrita
  (so `corpo`): respondido-por/respondida-em sao INJETADOS do ator/relogio na borda (anti-forge)."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.participacao.wire.in.resposta-titular :as wire]))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- so-esperados [m campos]
  (reduce (fn [acc k] (cond-> acc (contains? m k) (assoc (keyword k) (get m k)))) {} campos))

(def ^:private campos ["corpo"])

(defn coagir-resposta
  "Corpo JSON {corpo} (chaves STRING) -> mapa de dominio {:corpo}. ALLOWLIST descarta campo forjado
  (solicitacao_id/respondido_por); schema CLOSED + nao-vazio espelham a CHECK da mig 0041."
  [json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON {corpo}" {:campo :corpo}))
  (let [mp (so-esperados json-params campos)]
    (when-let [erros (m/explain wire/ResponderTitular mp)]
      (invalido! "corpo de resposta do titular invalido" {:campos (keys (me/humanize erros))}))
    (when (str/blank? (:corpo mp)) (invalido! "corpo obrigatorio nao pode ser vazio" {:campo :corpo}))
    {:corpo (:corpo mp)}))
