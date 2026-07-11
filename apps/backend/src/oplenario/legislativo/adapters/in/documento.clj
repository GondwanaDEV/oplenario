(ns oplenario.legislativo.adapters.in.documento
  "Gate de ENTRADA `wire/in -> models` do EXPEDIENTE (§22.10 adapters/in, ADR-0001, Onda B Slice 6). Chamado
  SO pelo diplomat/. Valida (fail-closed -> 400) e COAGE o corpo JSON p/ o dominio; INJETA o que nao vem do
  corpo (`id`/`created-by`/`updated-by` do ator, nunca do cliente, §22.5). `tipo-documento`/`corpo-template`
  do documento gerado NUNCA saem daqui: vem do MODELO ja' carregado, resolvido pelo CONTROLLER (fase 2) —
  este adapter so' ve o wire-in cru, nao o modelo."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.legislativo.wire.in.documento :as wire])
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

(def ^:private campos-gerar ["modelo-id" "assunto" "dados"])
(def ^:private campos-editar ["lock-version" "corpo" "assunto"])
(def ^:private campos-protocolar ["lock-version"])

(defn gerar-documento->dominio
  "Corpo (wire/in.GerarDocumento) + `ator` -> mapa PARCIAL de dominio p/ Repo/gerar-documento!. Gera `:id`
  (novo) + `:created-by` (do ator). `dados` ausente -> {} (o form sempre manda o mapa, mesmo vazio; ausencia
  e' tratada como 'sem fatos externos', nao erro). NAO inclui `:ente-id`/`:tipo-documento`/`:corpo-template`
  — o controller resolve o modelo (buscar-modelo) e junta esses campos antes de chamar o Repo."
  [ator wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [m (so-esperados wire-in campos-gerar)]
    (validar! wire/GerarDocumento m "corpo de gerar documento invalido")
    {:id (random-uuid) :modelo-id (->uuid (:modelo-id m) :modelo-id) :assunto (:assunto m)
     :dados (or (:dados m) {}) :created-by (:identidade-id ator)}))

(defn editar-documento->dominio
  "Corpo (wire/in.EditarDocumento) + `ator` + `id` (path, ja' UUID) -> mapa de dominio p/
  Repo/editar-documento!. `id` = o do path; `updated-by` = o ator — nunca do corpo (§22.5). `lock-version`
  obrigatorio (CAS real, mesmo contrato de wire/in/proposicao.EditarProposicao)."
  [ator id wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [m (so-esperados wire-in campos-editar)]
    (validar! wire/EditarDocumento m "corpo de editar documento invalido")
    {:id id :lock-version (:lock-version m) :corpo (:corpo m) :assunto (:assunto m)
     :updated-by (:identidade-id ator)}))

(defn protocolar-documento->dominio
  "Corpo (wire/in.ProtocolarDocumento) + `ator` + `id` (path, ja' UUID) -> mapa PARCIAL de dominio p/
  Repo/protocolar-documento!. NAO inclui `:ano`: a data civil e' resolvida pelo CONTROLLER via kernel/tempo
  (mesmo contrato de wire/in/parecer.emitir->dominio recebendo `agora` ja' resolvido) — este adapter e'
  traducao PURA wire->dominio, nao le relogio."
  [ator id wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [m (so-esperados wire-in campos-protocolar)]
    (validar! wire/ProtocolarDocumento m "corpo de protocolar documento invalido")
    {:documento-id id :lock-version (:lock-version m) :ator-id (:identidade-id ator)}))
