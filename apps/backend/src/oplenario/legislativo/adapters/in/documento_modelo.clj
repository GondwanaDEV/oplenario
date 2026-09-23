(ns oplenario.legislativo.adapters.in.documento-modelo
  "Gate de ENTRADA `wire/in -> models` do MODELO de documento (§22.10 adapters/in, ADR-0001, Onda B Slice 6,
  fatia de escrita). Chamado SO pelo diplomat/. Valida (fail-closed -> 400) e COAGE o corpo JSON p/ o
  dominio; INJETA o que nao vem do corpo (`id`/`created-by`/`updated-by` do ator, nunca do cliente, §22.5)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.legislativo.wire.in.documento-modelo :as wire]))

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

(def ^:private campos-criar ["chave" "nome" "tipo-documento" "corpo-template"])
(def ^:private campos-atualizar ["lock-version" "nome" "corpo-template" "ativo"])

(defn criar-modelo->dominio
  "Corpo (wire/in.CriarModelo) + `ator` -> mapa de dominio p/ Repo/criar-modelo!. Gera `:id` (novo) +
  `:created-by` (do ator)."
  [ator wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [m (so-esperados wire-in campos-criar)]
    (validar! wire/CriarModelo m "corpo de criar modelo invalido")
    {:id (random-uuid) :chave (:chave m) :nome (:nome m) :tipo-documento (:tipo-documento m)
     :corpo-template (:corpo-template m) :created-by (:identidade-id ator)}))

(defn atualizar-modelo->dominio
  "Corpo (wire/in.AtualizarModelo) + `ator` + `id` (path, ja' UUID) -> mapa de dominio p/
  Repo/atualizar-modelo!. `id` = o do path; `updated-by` = o ator — nunca do corpo (§22.5)."
  [ator id wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [m (so-esperados wire-in campos-atualizar)]
    (validar! wire/AtualizarModelo m "corpo de atualizar modelo invalido")
    {:id id :lock-version (:lock-version m) :nome (:nome m) :corpo-template (:corpo-template m)
     :ativo (:ativo m) :updated-by (:identidade-id ator)}))
