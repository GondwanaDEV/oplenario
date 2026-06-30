(ns oplenario.sessoes.adapters.in.gravacao
  "Gate de ENTRADA `query -> models` da INGESTAO de gravacao (§22.10 adapters/in, ADR-0001 §3). O corpo do
  request e' o BINARIO (container bruto); a metadata viaja por QUERY PARAMS (o corpo-json nao toca uploads).
  Valida (fail-closed -> 400) e coage para o dominio. So lê o ALLOWLIST de campos esperados — nunca confia em
  chave alheia. ente/autor NAO vem do cliente (vem do `ator`). Os enums espelham `sessoes.logic` (= os CHECK
  da mig 0031)."
  (:require [oplenario.sessoes.logic :as logic])
  (:import (java.time Instant)
           (java.time.format DateTimeParseException)
           (java.util UUID)))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- ->uuid [s campo]
  (try (UUID/fromString s) (catch IllegalArgumentException _ (invalido! "uuid invalido" {:campo campo}))))

(defn- ->instante [s campo]
  (when s
    (try (Instant/parse s) (catch DateTimeParseException _ (invalido! "instante invalido (ISO-8601)" {:campo campo})))))

(defn ingestao-meta->dominio
  "Query params (Pedestal: keyword-keyed) -> mapa de dominio p/ Repo/registrar-segmento!. Obrigatorios:
  `fonte-ingestao`, `motivo-inicio`, `iniciou-em`. Opcionais: `motivo-fim`, `encerrou-em`, `acesso-restrito`
  (default false), `sessao-id` (link-at-ingest). Valida enums contra `logic` (fail-closed -> 400, NUNCA 500 do
  CHECK do banco). NAO seta id/container-uri/hash/autor — o controller o faz apos transmitir ao store."
  [query-params]
  (let [qp            (or query-params {})
        fonte         (:fonte-ingestao qp)
        motivo-inicio (:motivo-inicio qp)
        iniciou       (:iniciou-em qp)
        motivo-fim    (:motivo-fim qp)]
    (when-not (and fonte motivo-inicio iniciou)
      (invalido! "metadata de ingestao incompleta" {:obrigatorios [:fonte-ingestao :motivo-inicio :iniciou-em]}))
    (when-not (contains? logic/fontes-ingestao-gravacao fonte)
      (invalido! "fonte-ingestao desconhecida" {:campo :fonte-ingestao}))
    (when-not (contains? logic/motivos-inicio-gravacao motivo-inicio)
      (invalido! "motivo-inicio desconhecido" {:campo :motivo-inicio}))
    (when (and motivo-fim (not (contains? logic/motivos-fim-gravacao motivo-fim)))
      (invalido! "motivo-fim desconhecido" {:campo :motivo-fim}))
    {:fonte-ingestao  fonte
     :motivo-inicio   motivo-inicio
     :motivo-fim      motivo-fim
     :iniciou-em      (->instante iniciou :iniciou-em)
     :encerrou-em     (->instante (:encerrou-em qp) :encerrou-em)
     :acesso-restrito (= "true" (:acesso-restrito qp))
     :sessao-id       (when-let [s (:sessao-id qp)] (->uuid s :sessao-id))}))

(defn vincular->dominio
  "Path params (sessao `:id` + segmento `:seg-id`, strings) + corpo JSON {lock-version} -> mapa de dominio p/
  controllers/vincular-gravacao (Opcao A pos-upload). Coage os uuids (malformado -> 400) e exige `lock-version`
  inteiro >= 0 (CAS otimista; ausente/nao-inteiro -> 400 fail-closed, NUNCA 500 do CHECK do banco). O corpo vem
  do corpo-json com chaves STRING (review W3): so le a chave esperada, nunca confia em chave alheia."
  [sessao-id-str seg-id-str json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON com lock-version" {:campo :corpo}))
  (let [lv (get json-params "lock-version")]
    ;; teto = Integer/MAX_VALUE: a coluna lock_version e' int4; um Long acima do teto passaria (integer?) mas
    ;; estouraria no CAS do banco com PSQLException -> 500. Barra na borda (fail-closed -> 400, nunca 500).
    (when-not (and (integer? lv) (<= 0 lv) (<= lv Integer/MAX_VALUE))
      (invalido! "lock-version ausente ou invalido (inteiro entre 0 e 2147483647)" {:campo :lock-version}))
    {:sessao-id    (->uuid sessao-id-str :id)
     :segmento-id  (->uuid seg-id-str :seg-id)
     :lock-version lv}))
