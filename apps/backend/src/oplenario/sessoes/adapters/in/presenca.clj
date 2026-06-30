(ns oplenario.sessoes.adapters.in.presenca
  "Gate de ENTRADA `wire/in -> models` da PRESENCA (§22.10 adapters/in, ADR-0001 §3) — eixo C. Chamado SO pelo
  diplomat/. Valida o corpo (fail-closed -> 400) contra wire/in.RegistrarPresenca e coage o dominio
  (uuid/Instant). So lê o ALLOWLIST de campos esperados (corpo-json = chaves STRING, review W3): chave alheia do
  cliente — incluida a `fonte` (forcada no servidor) — e' filtrada antes de tocar o dominio."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.sessoes.wire.in :as wire])
  (:import (java.time Instant)
           (java.time.format DateTimeParseException)
           (java.util UUID)))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- so-esperados
  "mapa STRING-keyed -> mapa keyword-keyed contendo SO os `campos` presentes (keyword literal, ja internada).
  Chave alheia do cliente NAO vira keyword (nem entra) — a allowlist e' o gate real (review W3)."
  [m campos]
  (reduce (fn [acc k] (cond-> acc (contains? m k) (assoc (keyword k) (get m k)))) {} campos))

(defn- ->uuid [s campo]
  (try (UUID/fromString s) (catch IllegalArgumentException _ (invalido! "uuid invalido" {:campo campo}))))

(defn- ->instante [s campo]
  ;; guard `when s` espelha adapters/in/gravacao.clj: hoje `ocorrido-em` e' obrigatorio (Malli barra nil antes
  ;; daqui), mas o contrato defensivo fica independente de quem chama — se o campo virar :optional, nil -> nil
  ;; (nao NPE silencioso).
  (when s
    (try (Instant/parse s) (catch DateTimeParseException _ (invalido! "instante invalido (ISO-8601)" {:campo campo})))))

(def ^:private campos-presenca ["vereador-id" "tipo" "modalidade" "ocorrido-em"])

(defn registrar-presenca->dominio
  "Path-param `:id` (sessao, string) + corpo JSON {vereador-id, tipo, modalidade, ocorrido-em} -> mapa de dominio
  p/ controllers/registrar-presenca. Coage os uuids (malformado -> 400) e o Instant (nao-ISO-8601 -> 400). Valida
  o contrato UMA vez (m/explain), guardando so os NOMES-de-campo humanizados (nunca o payload cru — review W3:
  m/explain embute :value, que vazaria PII p/ o log). A `fonte` NAO entra aqui (forcada no controller)."
  [sessao-id-str json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON {vereador-id, tipo, modalidade, ocorrido-em}" {:campo :corpo}))
  (let [m (so-esperados json-params campos-presenca)]
    (when-let [erros (m/explain wire/RegistrarPresenca m)]
      (invalido! "corpo de registrar presenca invalido" {:campos (keys (me/humanize erros))}))
    {:sessao-id   (->uuid sessao-id-str :id)
     :vereador-id (->uuid (:vereador-id m) :vereador-id)
     :tipo        (:tipo m)
     :modalidade  (:modalidade m)
     :ocorrido-em (->instante (:ocorrido-em m) :ocorrido-em)}))
