(ns oplenario.sessoes.adapters.in.sessao
  "Gate de ENTRADA `wire/in -> models` da sessao (§22.10 adapters/in, ADR-0001 §3) — dividido por DIRECAO (sob
  adapters/in/). Chamado SO pelo diplomat/. Valida e COAGE a representacao externa (JSON: strings) para o
  dominio (uuid/Instant), defendendo a borda (fail-closed). O nucleo (controllers/logic) so ve `models`."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.sessoes.logic :as logic]
            [oplenario.sessoes.wire.in :as wire])
  (:import (java.time Instant)
           (java.time.format DateTimeParseException)
           (java.util UUID)))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(def ^:private campos-agendar
  "As chaves esperadas do corpo (strings — :json-params vem com chaves STRING, sem keyword-interning, review
  seg W3 MAJOR-2). So estas sao promovidas a keyword; chaves alheias do cliente NAO viram keyword (nem entram)."
  ["sessao-legislativa-id" "tipo-sessao" "modalidade" "agendada-para"])

(defn- so-esperados
  "mapa STRING-keyed -> mapa keyword-keyed contendo SO os `campos` presentes (keyword literal, ja internada)."
  [m campos]
  (reduce (fn [acc k] (cond-> acc (contains? m k) (assoc (keyword k) (get m k)))) {} campos))

(defn id-param->uuid
  "Path-param :id (string) -> UUID. Malformado = requisicao invalida (`:validacao/invalido` -> 400 na borda),
  nunca erro interno (500)."
  [s]
  (try
    (UUID/fromString s)
    (catch IllegalArgumentException _ (invalido! "id de sessao invalido" {:campo :id}))))

(defn versao-param->int
  "Path-param :versao (string) -> int positivo (Etapa 5 fatia 5, rotas de leitura da folha). Malformado OU
  nao-positivo (versao da folha comeca em 1, D7) = requisicao invalida (`:validacao/invalido` -> 400 na
  borda), nunca erro interno (500) nem uma query que casaria contra `versao <= 0` em silencio."
  [s]
  (let [n (try (Integer/parseInt s) (catch NumberFormatException _ (invalido! "versao invalida" {:campo :versao})))]
    (if (pos-int? n) n (invalido! "versao invalida" {:campo :versao}))))

(defn- ->uuid [s campo]
  (try (UUID/fromString s) (catch IllegalArgumentException _ (invalido! "uuid invalido" {:campo campo}))))

(defn- ->instante [s campo]
  (when s
    (try (Instant/parse s) (catch DateTimeParseException _ (invalido! "instante invalido (ISO-8601)" {:campo campo})))))

(defn agendar-sessao->dominio
  "Corpo externo (wire/in.AgendarSessao) + `ator` -> mapa de dominio p/ Repo/agendar-sessao!. Valida o contrato
  (fail-closed -> 400), coage uuid/Instant, e INJETA o que nao vem do corpo: `id` (novo), `ente-id` e
  `created-by` (do ator) — nunca confia no cliente p/ tenant/autoria (§22.5)."
  [ator wire-in]
  (when-not (map? wire-in)
    (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [m (so-esperados wire-in campos-agendar)]
    ;; valida UMA vez; em falha guarda so os nomes-de-campo humanizados (NUNCA o payload cru — review W3:
    ;; m/explain embute :value, que vazaria PII do corpo p/ o log quando o F7 fiar logging de erro).
    (when-let [erros (m/explain wire/AgendarSessao m)]
      (invalido! "corpo de agendar sessao invalido" {:campos (keys (me/humanize erros))}))
    {:id                    (random-uuid)
     :ente-id               (:ente-id ator)
     :sessao-legislativa-id (->uuid (:sessao-legislativa-id m) :sessao-legislativa-id)
     :tipo-sessao           (:tipo-sessao m)
     :modalidade            (:modalidade m)
     :agendada-para         (->instante (:agendada-para m) :agendada-para)
     :created-by            (:identidade-id ator)}))

(def ^:private campos-transicao ["para" "motivo" "lock-version"])

(defn transicionar->dominio
  "Path-param `:id` (sessao, string) + corpo JSON {para, lock-version, motivo?} -> mapa de dominio p/
  controllers/transicionar-sessao (Mesa de conducao). Coage o uuid (malformado -> 400). Valida na borda
  (fail-closed -> 400, NUNCA 500 do db): `para` tem de ser um estado CONHECIDO (logic/estados-sessao) — a
  maquina (transicao VALIDA a partir do estado atual) decide depois no db (-> 409 se proibida); `lock-version`
  inteiro 0..int4 (teto = int4 da coluna, senao overflow -> 500); `motivo` obrigatorio quando `para` =
  'nao_realizada'. So le as chaves esperadas (chaves STRING do corpo-json, review W3)."
  [sessao-id-str json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON {para, lock-version}" {:campo :corpo}))
  (let [m      (so-esperados json-params campos-transicao)
        para   (:para m)
        motivo (:motivo m)
        lv     (:lock-version m)]
    (when-not (contains? logic/estados-sessao para)
      (invalido! "estado-alvo desconhecido" {:campo :para}))
    ;; teto ESTRITO < int4-max: o db faz lock_version+1 no CAS — aceitar o proprio MAX_VALUE estouraria o int4
    ;; (review clojure LOW). A borda casa o range SEGURO da coluna.
    (when-not (and (integer? lv) (<= 0 lv) (< lv Integer/MAX_VALUE))
      (invalido! "lock-version ausente ou invalido (inteiro entre 0 e 2147483646)" {:campo :lock-version}))
    (when (and (= "nao_realizada" para) (str/blank? motivo))
      (invalido! "transicao p/ 'nao_realizada' exige motivo" {:campo :motivo}))
    ;; teto de campo livre (defense-in-depth, review sec LOW): motivo vai p/ uma coluna TEXT — barra na borda
    ;; um string desmesurado de um secretario autenticado (-> 400), sem confiar so no audience.
    (when (and (string? motivo) (> (count motivo) 2000))
      (invalido! "motivo longo demais (max 2000)" {:campo :motivo}))
    {:sessao-id    (->uuid sessao-id-str :id)
     :para         para
     :motivo       (when-not (str/blank? motivo) motivo)
     :lock-version lv}))
