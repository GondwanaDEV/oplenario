(ns oplenario.sessoes.adapters.in.tribuna
  "Gate de ENTRADA `wire/in -> models` da TRIBUNA (§22.10 adapters/in, ADR-0001 §3) — eixo F. Chamado SO pelo
  diplomat/. Valida (fail-closed -> 400) e coage o dominio (uuid). So lê o ALLOWLIST de campos esperados
  (corpo-json = chaves STRING, review W3). ente/autor NAO vem do cliente (vem do `ator`)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.sessoes.wire.in :as wire])
  (:import (java.util UUID)))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- so-esperados
  [m campos]
  (reduce (fn [acc k] (cond-> acc (contains? m k) (assoc (keyword k) (get m k)))) {} campos))

(defn- ->uuid [s campo]
  (try (UUID/fromString s) (catch IllegalArgumentException _ (invalido! "uuid invalido" {:campo campo}))))

(def ^:private campos-inscrever ["vereador-id" "origem-inscricao" "fase" "proposicao-ref-id"])

(defn inscrever->dominio
  "Path-param `:id` (sessao) + corpo JSON {vereador-id, origem-inscricao, fase, proposicao-ref-id?} -> mapa de
  dominio p/ controllers/inscrever-orador. Valida o contrato UMA vez (m/explain, so os NOMES-de-campo no erro —
  nunca o payload cru, review W3) e coage os uuids (malformado -> 400). `proposicao-ref-id` opcional."
  [sessao-id-str json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON {vereador-id, origem-inscricao, fase}" {:campo :corpo}))
  (let [mp (so-esperados json-params campos-inscrever)]
    (when-let [erros (m/explain wire/InscreverOrador mp)]
      (invalido! "corpo de inscrever orador invalido" {:campos (keys (me/humanize erros))}))
    (cond-> {:sessao-id        (->uuid sessao-id-str :id)
             :vereador-id      (->uuid (:vereador-id mp) :vereador-id)
             :origem-inscricao (:origem-inscricao mp)
             :fase             (:fase mp)}
      (:proposicao-ref-id mp) (assoc :proposicao-ref-id (->uuid (:proposicao-ref-id mp) :proposicao-ref-id)))))

(defn desistir->dominio
  "Path-params `:id` (sessao) + `:insc-id` (inscricao) + corpo JSON {lock-version} -> mapa de dominio p/
  controllers/desistir-inscricao. Coage os uuids (malformado -> 400) e exige `lock-version` inteiro 0..int4 (CAS
  otimista; ausente/fora do range -> 400 fail-closed, NUNCA 500 do CHECK do banco). Le a chave STRING do corpo."
  [sessao-id-str insc-id-str json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON com lock-version" {:campo :corpo}))
  (let [lv (get json-params "lock-version")]
    (when-not (and (integer? lv) (<= 0 lv) (<= lv Integer/MAX_VALUE))
      (invalido! "lock-version ausente ou invalido (inteiro entre 0 e 2147483647)" {:campo :lock-version}))
    {:sessao-id     (->uuid sessao-id-str :id)
     :inscricao-id  (->uuid insc-id-str :insc-id)
     :lock-version  lv}))
