(ns oplenario.legislativo.adapters.in.votacao
  "Gate de ENTRADA `wire/in -> models` da votacao ao vivo (§22.10 adapters/in, ADR-0001 §3). Chamado SO pelo
  diplomat/. Valida (fail-closed -> 400) e COAGE a representacao externa (JSON: strings) p/ o dominio
  (uuid/int), defendendo a borda. INJETA o que nao vem do corpo: `id` novo, `created-by`/`updated-by` (do ator).
  As chaves do corpo sao STRING (:json-params, anti keyword-interning) — so as esperadas viram keyword."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.legislativo.wire.in.votacao :as wire])
  (:import (java.util UUID)))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- so-esperados
  "mapa STRING-keyed -> mapa keyword-keyed contendo SO os `campos` presentes (keyword ja internada)."
  [m campos]
  (reduce (fn [acc k] (cond-> acc (contains? m k) (assoc (keyword k) (get m k)))) {} campos))

(defn id-param->uuid
  "Path-param (string) -> UUID. Malformado = `:validacao/invalido` -> 400 na borda, nunca 500."
  [s]
  (try (UUID/fromString s) (catch IllegalArgumentException _ (invalido! "id invalido (path)" {:campo :id}))))

(defn- ->uuid [s campo]
  (try (UUID/fromString s) (catch IllegalArgumentException _ (invalido! "uuid invalido" {:campo campo}))))

(defn- ->uuid? [s campo] (when (some? s) (->uuid s campo)))

(defn- validar! [schema m msg]
  (when-let [erros (m/explain schema m)]
    ;; guarda so os nomes-de-campo humanizados (NUNCA o payload cru — m/explain embute :value = vazaria dado).
    (invalido! msg {:campos (keys (me/humanize erros))})))

(def ^:private campos-abrir ["objeto-tipo" "objeto-id" "modalidade" "quorum-tipo" "pauta-item-id"])
(def ^:private campos-voto ["voto" "vereador-id"])
(def ^:private campos-encerrar ["lock-version" "base-membros" "resultado"])

(defn abrir-votacao->dominio
  "Corpo externo (wire/in.AbrirVotacao) + `ator` -> mapa de dominio p/ Repo/abrir-votacao!. O sessao-id (da URL)
  e' adicionado pelo controller. Gera o `id` da votacao e o `created-by` (do ator) — nunca do cliente (§22.5)."
  [ator wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [m (so-esperados wire-in campos-abrir)]
    (validar! wire/AbrirVotacao m "corpo de abrir votacao invalido")
    {:id            (random-uuid)
     :objeto-tipo   (:objeto-tipo m)
     :objeto-id     (->uuid (:objeto-id m) :objeto-id)
     :modalidade    (:modalidade m)
     :quorum-tipo   (:quorum-tipo m)
     :pauta-item-id (->uuid? (:pauta-item-id m) :pauta-item-id)
     :created-by    (:identidade-id ator)}))

(defn registrar-voto->dominio
  "Corpo (wire/in.RegistrarVoto) + `ator` + `votacao-id` (UUID coagido do path) -> mapa de dominio. Gera o `id`
  do voto. `vereador-id` so e' coagido se presente — na modalidade SECRETA o controller o ignora (sigilo §22.6)."
  [ator votacao-id wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [m (so-esperados wire-in campos-voto)]
    (validar! wire/RegistrarVoto m "corpo de registrar voto invalido")
    {:id          (random-uuid)
     :votacao-id  votacao-id
     :voto        (:voto m)
     :vereador-id (->uuid? (:vereador-id m) :vereador-id)
     :created-by  (:identidade-id ator)}))

(defn encerrar-votacao->dominio
  "Corpo (wire/in.EncerrarVotacao) + `ator` + `votacao-id` (UUID coagido do path) -> mapa de dominio p/
  Repo/encerrar-votacao!. `id` = o votacao-id do path; `updated-by` = o ator."
  [ator votacao-id wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [m (so-esperados wire-in campos-encerrar)]
    (validar! wire/EncerrarVotacao m "corpo de encerrar votacao invalido")
    {:id           votacao-id
     :lock-version (:lock-version m)
     :base-membros (:base-membros m)
     :resultado    (:resultado m)
     :updated-by   (:identidade-id ator)}))
