(ns oplenario.sessoes.adapters.in.presenca
  "Gate de ENTRADA `wire/in -> models` da PRESENCA (§22.10 adapters/in, ADR-0001 §3) — eixo C. Chamado SO pelo
  diplomat/. Valida o corpo (fail-closed -> 400) contra wire/in.RegistrarPresenca e coage o dominio
  (uuid/Instant). So lê o ALLOWLIST de campos esperados (corpo-json = chaves STRING, review W3): chave alheia do
  cliente — incluida a `fonte` (forcada no servidor) — e' filtrada antes de tocar o dominio."
  (:require [clojure.string :as str]
            [malli.core :as m]
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

;; ---------- justificativa de ausencia (Etapa 2 da chamada) ----------

(defn- ->motivo
  "`motivo` obrigatorio e NAO-VAZIO apos trim: o CHECK `length(trim(motivo)) > 0` da mig 0029 e' o ultimo
  fail-closed, mas deixa-lo decidir daria 500 (bug de servidor) onde e' 400 (corpo do cliente). Devolve o
  texto CRU (sem trim aplicado) — normalizar o que o operador digitou e' decisao de dominio, nao de borda."
  [s]
  (when (or (not (string? s)) (str/blank? s))
    (invalido! "motivo ausente ou em branco" {:campo :motivo}))
  s)

(defn- ->int4
  "Inteiro 0..Integer/MAX_VALUE (cabe no int4 do banco). Ausente/fora do range -> 400 fail-closed, NUNCA 500
  por overflow. Espelha o `->int4` de adapters/in/pauta (o CAS tem o mesmo contrato em todo o modulo)."
  [v campo]
  (when-not (and (integer? v) (<= 0 v) (<= v Integer/MAX_VALUE))
    (invalido! "inteiro ausente ou fora do range (0..2147483647)" {:campo campo}))
  v)

(def ^:private campos-abrir-justificativa ["vereador-id" "motivo"])
(def ^:private campos-abrir-minha-justificativa ["motivo"])
(def ^:private campos-decidir-justificativa ["estado" "lock-version"])

(defn abrir-justificativa->dominio
  "Path-param `:id` (sessao) + corpo JSON {vereador-id, motivo} -> mapa de dominio p/
  controllers/abrir-justificativa (a porta da MESA). Coage o uuid (malformado -> 400) e exige motivo nao-vazio.
  Valida o contrato UMA vez (m/explain), guardando so os NOMES-de-campo humanizados — nunca o payload cru:
  `m/explain` embute `:value`, e aqui o value e' justamente o `motivo`, que pode ser dado de saude (LGPD).
  `estado` NAO entra (nasce 'pendente' no servidor) e `created-by` vem do ator."
  [sessao-id-str json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON {vereador-id, motivo}" {:campo :corpo}))
  (let [m (so-esperados json-params campos-abrir-justificativa)]
    (when-let [erros (m/explain wire/AbrirJustificativa m)]
      (invalido! "corpo de abrir justificativa invalido" {:campos (keys (me/humanize erros))}))
    {:sessao-id   (->uuid sessao-id-str :id)
     :vereador-id (->uuid (:vereador-id m) :vereador-id)
     :motivo      (->motivo (:motivo m))}))

(defn abrir-minha-justificativa->dominio
  "Path-param `:id` (sessao) + corpo JSON {motivo} -> mapa de dominio p/ controllers/abrir-minha-justificativa
  (a porta SELF-SERVICE do vereador). O allowlist NAO contem `vereador-id`: um corpo que o traga e' filtrado
  antes de tocar o dominio — e' assim que a forja de 'justificar a falta de outro' morre na borda, e nao numa
  checagem que alguem pode esquecer de fazer depois."
  [sessao-id-str json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON {motivo}" {:campo :corpo}))
  (let [m (so-esperados json-params campos-abrir-minha-justificativa)]
    (when-let [erros (m/explain wire/AbrirMinhaJustificativa m)]
      (invalido! "corpo de abrir justificativa invalido" {:campos (keys (me/humanize erros))}))
    {:sessao-id (->uuid sessao-id-str :id)
     :motivo    (->motivo (:motivo m))}))

(defn decidir-justificativa->dominio
  "Path-params `:id` (sessao) + `:jid` (justificativa) + corpo JSON {estado, lock-version} -> mapa de dominio
  p/ controllers/decidir-justificativa. `estado` so' os TERMINAIS (o enum de wire/in barra 'pendente' -> 400,
  antes de a maquina de estados ser consultada); `lock-version` inteiro 0..int4 (CAS obrigatorio).
  `decidido-por` NAO entra do cliente (injetado do ator no controller)."
  [sessao-id-str jid-str json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON {estado, lock-version}" {:campo :corpo}))
  (let [m (so-esperados json-params campos-decidir-justificativa)]
    (when-let [erros (m/explain wire/DecidirJustificativa m)]
      (invalido! "corpo de decidir justificativa invalido" {:campos (keys (me/humanize erros))}))
    {:sessao-id        (->uuid sessao-id-str :id)
     :justificativa-id (->uuid jid-str :jid)
     :estado           (:estado m)
     :lock-version     (->int4 (:lock-version m) :lock-version)}))
