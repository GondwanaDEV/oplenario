(ns oplenario.sessoes.adapters.in.tribuna
  "Gate de ENTRADA `wire/in -> models` da TRIBUNA (§22.10 adapters/in, ADR-0001 §3) — eixo F. Chamado SO pelo
  diplomat/. Valida (fail-closed -> 400) e coage o dominio (uuid). So lê o ALLOWLIST de campos esperados
  (corpo-json = chaves STRING, review W3). ente/autor NAO vem do cliente (vem do `ator`)."
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
  [m campos]
  (reduce (fn [acc k] (cond-> acc (contains? m k) (assoc (keyword k) (get m k)))) {} campos))

(defn- ->uuid [s campo]
  (try (UUID/fromString s) (catch IllegalArgumentException _ (invalido! "uuid invalido" {:campo campo}))))

(defn- ->instante [s campo]
  ;; nil-tolerante (espelha adapters/in/presenca): hoje os instantes sao obrigatorios (Malli barra nil antes
  ;; daqui), mas o contrato fica independente de quem chama — campo :optional viraria nil -> nil, nunca NPE.
  (when s
    (try (Instant/parse s) (catch DateTimeParseException _ (invalido! "instante invalido (ISO-8601)" {:campo campo})))))

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

;; ---------- fala_executada + cronometro (execucao, F4.5b) ----------

(def ^:private campos-iniciar
  ["orador-id" "tipo-fala" "fase" "iniciou-em" "inscricao-id" "fala-pai-id" "proposicao-ref-id"])

(defn iniciar-fala->dominio
  "Path-param `:id` (sessao) + corpo JSON {orador-id, tipo-fala, fase, iniciou-em, inscricao-id?, fala-pai-id?,
  proposicao-ref-id?} -> mapa de dominio p/ controllers/iniciar-fala. Valida o contrato UMA vez (m/explain, so os
  NOMES-de-campo no erro — nunca o payload cru, review W3), coage os uuids (malformado -> 400) e o Instant
  (nao-ISO -> 400). Os tres uuids opcionais so entram se presentes."
  [sessao-id-str json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON {orador-id, tipo-fala, fase, iniciou-em}" {:campo :corpo}))
  (let [mp (so-esperados json-params campos-iniciar)]
    (when-let [erros (m/explain wire/IniciarFala mp)]
      (invalido! "corpo de iniciar fala invalido" {:campos (keys (me/humanize erros))}))
    (cond-> {:sessao-id  (->uuid sessao-id-str :id)
             :orador-id  (->uuid (:orador-id mp) :orador-id)
             :tipo-fala  (:tipo-fala mp)
             :fase       (:fase mp)
             :iniciou-em (->instante (:iniciou-em mp) :iniciou-em)}
      (:inscricao-id mp)      (assoc :inscricao-id (->uuid (:inscricao-id mp) :inscricao-id))
      (:fala-pai-id mp)       (assoc :fala-pai-id (->uuid (:fala-pai-id mp) :fala-pai-id))
      (:proposicao-ref-id mp) (assoc :proposicao-ref-id (->uuid (:proposicao-ref-id mp) :proposicao-ref-id)))))

(def ^:private campos-cronometro ["tipo" "ocorrido-em" "segundos-adicionais"])

(defn- validar-coerencia-cronometro!
  "Coerencia tipo<->segundos-adicionais (espelha logic/validar-evento-cronometro, mas na BORDA -> 400, nunca o
  CHECK da mig 0033 -> 500): 'tempo_adicional_concedido' EXIGE segundos > 0; os demais tipos PROIBEM o campo."
  [tipo seg]
  (if (= "tempo_adicional_concedido" tipo)
    ;; `integer?` (nao `int?`): espelha o predicado do schema Malli `:int` (aceita Long E Integer); `int?` so e'
    ;; Long e barraria um Integer valido (review clj). Tambem nil-guard. Teto int4 = mesma defesa do lock-version
    ;; (sem ele, Long alem de int4 estoura o driver -> 500, review sec).
    (when-not (and (integer? seg) (pos? seg) (<= seg Integer/MAX_VALUE))
      (invalido! "tempo_adicional_concedido exige segundos-adicionais inteiro entre 1 e 2147483647" {:campo :segundos-adicionais}))
    (when (some? seg)
      (invalido! "so tempo_adicional_concedido carrega segundos-adicionais" {:campo :segundos-adicionais}))))

(defn cronometro->dominio
  "Path-params `:id` (sessao) + `:fala-id` (fala) + corpo JSON {tipo, ocorrido-em, segundos-adicionais?} -> mapa
  de dominio p/ controllers/registrar-evento-cronometro. Valida o contrato (m/explain) + a COERENCIA
  tipo<->segundos na borda (fail-closed -> 400), coage os uuids e o Instant. `segundos-adicionais` so entra se
  presente."
  [sessao-id-str fala-id-str json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON {tipo, ocorrido-em}" {:campo :corpo}))
  (let [mp  (so-esperados json-params campos-cronometro)]
    (when-let [erros (m/explain wire/RegistrarEventoCronometro mp)]
      (invalido! "corpo de evento de cronometro invalido" {:campos (keys (me/humanize erros))}))
    (validar-coerencia-cronometro! (:tipo mp) (:segundos-adicionais mp))
    (cond-> {:sessao-id   (->uuid sessao-id-str :id)
             :fala-id     (->uuid fala-id-str :fala-id)
             :tipo        (:tipo mp)
             :ocorrido-em (->instante (:ocorrido-em mp) :ocorrido-em)}
      (:segundos-adicionais mp) (assoc :segundos-adicionais (:segundos-adicionais mp)))))

(def ^:private campos-encerrar ["encerrou-em" "lock-version"])

(defn encerrar-fala->dominio
  "Path-params `:id` (sessao) + `:fala-id` (fala) + corpo JSON {encerrou-em, lock-version} -> mapa de dominio p/
  controllers/encerrar-fala. Valida o contrato (m/explain) + range int4 do `lock-version` (CAS otimista;
  fora do range -> 400 fail-closed, NUNCA 500 do CHECK do banco), coage os uuids e o Instant."
  [sessao-id-str fala-id-str json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON {encerrou-em, lock-version}" {:campo :corpo}))
  (let [mp (so-esperados json-params campos-encerrar)]
    (when-let [erros (m/explain wire/EncerrarFala mp)]
      (invalido! "corpo de encerrar fala invalido" {:campos (keys (me/humanize erros))}))
    (let [lv (:lock-version mp)]
      (when-not (and (<= 0 lv) (<= lv Integer/MAX_VALUE))
        (invalido! "lock-version invalido (inteiro entre 0 e 2147483647)" {:campo :lock-version}))
      {:sessao-id    (->uuid sessao-id-str :id)
       :fala-id      (->uuid fala-id-str :fala-id)
       :encerrou-em  (->instante (:encerrou-em mp) :encerrou-em)
       :lock-version lv})))

;; ---------- decisao_mesa (questao de ordem, append-only — F4.5c) ----------

(def ^:private campos-decisao ["questao" "decisao" "decidido-em" "fundamentacao" "fala-id"])

(defn- exigir-nao-vazio!
  "Validacao PURA (so efeito de lancar): espelha na BORDA o CHECK `length(trim(x)) > 0` da migration 0034
  (fail-closed -> 400, nunca o CHECK -> 500). `s` ja' passou pelo Malli `:string` (nunca nil aqui p/ campo
  obrigatorio); rejeita vazio/so-espacos. NAO devolve valor util (nao faz trim — o texto vai cru ao dominio)."
  [s campo]
  (when (str/blank? s)
    (invalido! "campo de texto obrigatorio nao pode ser vazio" {:campo campo})))

(defn decisao-mesa->dominio
  "Path-param `:id` (sessao) + corpo JSON {questao, decisao, decidido-em, fundamentacao?, fala-id?} -> mapa de
  dominio p/ controllers/registrar-decisao-mesa. Valida o contrato (m/explain, so os NOMES-de-campo no erro —
  nunca o payload cru, review W3), espelha o CHECK `length(trim)>0` de questao/decisao (e da fundamentacao se
  presente) na borda (-> 400), coage o Instant (nao-ISO -> 400) e o `fala-id` (uuid; -> 400). `presidente-id` NAO
  entra aqui — e' injetado do ator no controller (um cliente nao forja quem decidiu)."
  [sessao-id-str json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON {questao, decisao, decidido-em}" {:campo :corpo}))
  (let [mp (so-esperados json-params campos-decisao)]
    (when-let [erros (m/explain wire/RegistrarDecisaoMesa mp)]
      (invalido! "corpo de decisao da mesa invalido" {:campos (keys (me/humanize erros))}))
    (exigir-nao-vazio! (:questao mp) :questao)
    (exigir-nao-vazio! (:decisao mp) :decisao)
    (when (some? (:fundamentacao mp)) (exigir-nao-vazio! (:fundamentacao mp) :fundamentacao))
    (cond-> {:sessao-id   (->uuid sessao-id-str :id)
             :questao     (:questao mp)
             :decisao     (:decisao mp)
             :decidido-em (->instante (:decidido-em mp) :decidido-em)}
      (:fundamentacao mp) (assoc :fundamentacao (:fundamentacao mp))
      (:fala-id mp)       (assoc :fala-id (->uuid (:fala-id mp) :fala-id)))))
