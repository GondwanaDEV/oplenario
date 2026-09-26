(ns oplenario.legislativo.adapters.in.requerimento
  "Gate de ENTRADA `wire/in -> dominio` do requerimento do vereador (§22.10 adapters/in, ADR-0001, fatia 2a).
  Chamado SO pelo diplomat/. Valida (fail-closed -> 400) e coage; gera o `:id` da proposicao no protocolo.
  NAO injeta autor nem data: o controller resolve do login e o diplomat passa o `hoje` do relogio."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.legislativo.wire.in.requerimento :as wire])
  (:import (java.util UUID)))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- so-esperados [m campos]
  (reduce (fn [acc k] (cond-> acc (contains? m k) (assoc (keyword k) (get m k)))) {} campos))

(defn- validar! [schema m msg]
  (when-let [erros (m/explain schema m)]
    ;; so' os nomes-de-campo (nunca o payload cru — m/explain embute :value)
    (invalido! msg {:campos (keys (me/humanize erros))})))

(defn- ->uuid [s campo]
  (try (UUID/fromString s) (catch IllegalArgumentException _ (invalido! "uuid invalido" {:campo campo}))))

(defn previa->dominio
  "Corpo (wire/in.PreviaRequerimento) -> {:modelo-id :campos}."
  [json]
  (when-not (map? json) (invalido! "corpo deve ser objeto JSON {modelo-id, campos}" {:campo :corpo}))
  (let [mp (so-esperados json ["modelo-id" "campos"])]
    (validar! wire/PreviaRequerimento mp "corpo de previa de requerimento invalido")
    {:modelo-id (->uuid (:modelo-id mp) :modelo-id) :campos (or (:campos mp) {})}))

(defn protocolar->dominio
  "Corpo (wire/in.ProtocolarRequerimento) -> {:id :modelo-id :campos :ementa}. `:id` novo (a proposicao)."
  [json]
  (when-not (map? json) (invalido! "corpo deve ser objeto JSON {modelo-id, campos, ementa}" {:campo :corpo}))
  (let [mp (so-esperados json ["modelo-id" "campos" "ementa"])]
    (validar! wire/ProtocolarRequerimento mp "corpo de protocolo de requerimento invalido")
    (when (re-matches #"\s*" (:ementa mp))
      (invalido! "ementa nao pode ser vazia" {:campo :ementa}))
    {:id (random-uuid) :modelo-id (->uuid (:modelo-id mp) :modelo-id) :campos (or (:campos mp) {})
     :ementa (:ementa mp)}))
