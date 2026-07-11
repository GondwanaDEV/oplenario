(ns oplenario.legislativo.adapters.in.ciencia
  "Gate de ENTRADA `wire/in -> models` da ciencia do vereador (§22.10 adapters/in, ADR-0001, Onda C1).
  Chamado SO pelo diplomat/. Valida (fail-closed -> 400) e COAGE o corpo JSON p/ o dominio; gera `:id`
  novo. `vereador-id` NAO e' injetado aqui — o adapter nao tem acesso ao resolver; o CONTROLLER injeta,
  resolvido do ator (anti-forja, mesmo contrato de `meu-painel`)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.legislativo.wire.in.ciencia :as wire])
  (:import (java.util UUID)))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- so-esperados
  "mapa STRING-keyed -> mapa keyword-keyed contendo SO os `campos` presentes (keyword ja internada)."
  [m campos]
  (reduce (fn [acc k] (cond-> acc (contains? m k) (assoc (keyword k) (get m k)))) {} campos))

(defn- validar! [schema m msg]
  (when-let [erros (m/explain schema m)]
    ;; guarda so os nomes-de-campo humanizados (NUNCA o payload cru — m/explain embute :value = vazaria dado).
    (invalido! msg {:campos (keys (me/humanize erros))})))

(defn- ->uuid [s campo]
  (try (UUID/fromString s) (catch IllegalArgumentException _ (invalido! "uuid invalido" {:campo campo}))))

(def ^:private campos-acusar ["evento-ref" "tipo"])

(defn acusar-ciencia->dominio
  "Corpo (wire/in.AcusarCiencia) -> mapa PARCIAL de dominio p/ controllers/acusar-ciencia. Gera `:id`
  novo; NAO inclui `:vereador-id` (o controller injeta, resolvido do ator via `resolver-vereador`)."
  [wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [m (so-esperados wire-in campos-acusar)]
    (validar! wire/AcusarCiencia m "corpo de acusar ciencia invalido")
    {:id (random-uuid) :evento-ref (->uuid (:evento-ref m) :evento-ref) :tipo (:tipo m)}))
