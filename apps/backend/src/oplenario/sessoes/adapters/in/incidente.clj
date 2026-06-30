(ns oplenario.sessoes.adapters.in.incidente
  "Gate de ENTRADA `wire/in -> models` dos INCIDENTES PROCESSUAIS (§16.13, §22.10 adapters/in). Chamado SO pelo
  diplomat/. Valida (fail-closed -> 400) e coage o dominio: tipo/resultado contra o enum (espelha o CHECK da mig
  0035), descricao/deliberacao nao-vazias (espelha length(trim)>0), coerencia objeto-tipo<->objeto-id (ambos ou
  nenhum), uuids e Instant. So lê o ALLOWLIST de campos esperados (corpo-json = chaves STRING). created-by/tenant
  NAO vem do cliente (vem do `ator`)."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.sessoes.wire.in :as wire])
  (:import (java.time Instant)
           (java.time.format DateTimeParseException)
           (java.util UUID)))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- so-esperados [m campos]
  (reduce (fn [acc k] (cond-> acc (contains? m k) (assoc (keyword k) (get m k)))) {} campos))

(defn- ->uuid [s campo]
  (try (UUID/fromString s) (catch IllegalArgumentException _ (invalido! "uuid invalido" {:campo campo}))))

(defn- ->instante [s campo]
  (when s
    (try (Instant/parse s) (catch DateTimeParseException _ (invalido! "instante invalido (ISO-8601)" {:campo campo})))))

(defn- exigir-nao-vazio!
  "Espelha na BORDA o CHECK length(trim(x))>0 da mig 0035 (-> 400, nunca 500). `s` ja' passou pelo Malli :string."
  [s campo]
  (when (str/blank? s)
    (invalido! "campo de texto obrigatorio nao pode ser vazio" {:campo campo})))

(def ^:private campos
  ["tipo" "resultado" "descricao" "ocorrido-em" "objeto-tipo" "objeto-id" "requerente-id" "deliberacao"])

(defn registrar->dominio
  "Path-param `:id` (sessao) + corpo JSON {tipo, resultado, descricao, ocorrido-em, objeto-tipo?, objeto-id?,
  requerente-id?, deliberacao?} -> mapa de dominio p/ controllers/registrar-incidente. Valida o contrato (m/explain,
  so os NOMES-de-campo no erro — nunca o payload cru), o enum de tipo/resultado, o nao-vazio de descricao (e da
  deliberacao se presente), a COERENCIA objeto-tipo<->objeto-id, e coage uuids + Instant. `created-by` NAO entra
  aqui — injetado do ator no controller."
  [sessao-id-str json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON {tipo, resultado, descricao, ocorrido-em}" {:campo :corpo}))
  (let [mp (so-esperados json-params campos)]
    ;; tipo/resultado/objeto-tipo (enums) + caps de descricao/deliberacao sao validados pelo m/explain (o schema
    ;; usa km/enum-de + :max) -> 400; aqui so o que o Malli flat nao expressa: nao-vazio (trim) e coerencia.
    (when-let [erros (m/explain wire/RegistrarIncidente mp)]
      (invalido! "corpo de incidente processual invalido" {:campos (keys (me/humanize erros))}))
    (exigir-nao-vazio! (:descricao mp) :descricao)
    (when (some? (:deliberacao mp)) (exigir-nao-vazio! (:deliberacao mp) :deliberacao))
    ;; coerencia objeto: ambos ou nenhum (espelha o CHECK incidente_objeto_coerente) — barra na borda -> 400.
    (when (not= (some? (:objeto-tipo mp)) (some? (:objeto-id mp)))
      (invalido! "objeto-tipo e objeto-id sao coerentes: informe ambos ou nenhum" {:campo :objeto}))
    (cond-> {:sessao-id   (->uuid sessao-id-str :id)
             :tipo        (:tipo mp)
             :resultado   (:resultado mp)
             :descricao   (:descricao mp)
             :ocorrido-em (->instante (:ocorrido-em mp) :ocorrido-em)}
      (:objeto-tipo mp)   (assoc :objeto-tipo (:objeto-tipo mp))
      (:objeto-id mp)     (assoc :objeto-id (->uuid (:objeto-id mp) :objeto-id))
      (:requerente-id mp) (assoc :requerente-id (->uuid (:requerente-id mp) :requerente-id))
      (:deliberacao mp)   (assoc :deliberacao (:deliberacao mp)))))
