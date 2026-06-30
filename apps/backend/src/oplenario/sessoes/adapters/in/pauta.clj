(ns oplenario.sessoes.adapters.in.pauta
  "Gate de ENTRADA `wire/in -> models` da PAUTA viva (§22.10 adapters/in, ADR-0001 §3) — eixo B. Chamado SO pelo
  diplomat/. Valida (fail-closed -> 400) e coage o dominio (uuid, ranges int4). So lê o ALLOWLIST de campos
  esperados (corpo-json = chaves STRING, review W3). ente/autor NAO vem do cliente (vem do `ator`); `ordem` e' o
  unico inteiro numerado server-side (max+1) — o cliente nunca a envia ao adicionar."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.sessoes.logic :as logic]
            [oplenario.sessoes.wire.in :as wire])
  (:import (java.util UUID)))

(set! *warn-on-reflection* true)

(def ^:private ^:const max-texto
  "Teto de texto livre (texto-descricao do item, justificativa da remocao). Espelha o cap de `motivo` da
  transicao (sessao adapter, 2000): sem ele o unico limite seria o corpo-json (256 KiB), o que deixa um
  secretario amplificar storage no banco multi-tenant (review sec MEDIUM). Estouro -> 400 na borda."
  2000)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- texto-no-limite!
  "Barra texto livre acima de `max-texto` -> 400 (nunca confia so no corpo-json p/ limitar campo individual)."
  [s campo]
  (when (and (string? s) (> (count s) max-texto))
    (invalido! (str (name campo) " longo demais (max " max-texto ")") {:campo campo}))
  s)

(defn- so-esperados
  [m campos]
  (reduce (fn [acc k] (cond-> acc (contains? m k) (assoc (keyword k) (get m k)))) {} campos))

(defn- ->uuid [s campo]
  (try (UUID/fromString s) (catch IllegalArgumentException _ (invalido! "uuid invalido" {:campo campo}))))

(defn- ->int4
  "Inteiro 0..Integer/MAX_VALUE (cabe no int4 do banco). Ausente/fora do range -> 400 fail-closed, NUNCA 500 do
  CHECK/overflow do banco. Cobre `ordem` (sort hint) e `lock-version` (CAS otimista)."
  [v campo]
  (when-not (and (integer? v) (<= 0 v) (<= v Integer/MAX_VALUE))
    (invalido! (str (name campo) " ausente ou invalido (inteiro entre 0 e 2147483647)") {:campo campo}))
  v)

(def ^:private campos-adicionar ["fase" "tipo-item" "proposicao-id" "texto-descricao"])

(defn adicionar-item->dominio
  "Path-param `:id` (sessao) + corpo JSON {fase, tipo-item, proposicao-id? | texto-descricao?} -> mapa de
  dominio p/ controllers/adicionar-item-pauta. Valida o contrato UMA vez (m/explain, so os NOMES-de-campo no
  erro — nunca o payload cru, review W3) INCL. a FK-por-tipo (proposicao XOR texto, no `:fn` do schema) e coage
  os uuids. `proposicao-id`/`texto-descricao` mutuamente exclusivos por tipo (garantido pelo schema)."
  [sessao-id-str json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON {fase, tipo-item, proposicao-id | texto-descricao}" {:campo :corpo}))
  (let [mp (so-esperados json-params campos-adicionar)]
    (when-let [erros (m/explain wire/AdicionarItemPauta mp)]
      (invalido! "corpo de adicionar item invalido" {:campos (keys (me/humanize erros))}))
    (cond-> {:sessao-id (->uuid sessao-id-str :id)
             :fase      (:fase mp)
             :tipo-item (:tipo-item mp)}
      (:proposicao-id mp)   (assoc :proposicao-id (->uuid (:proposicao-id mp) :proposicao-id))
      (:texto-descricao mp) (assoc :texto-descricao (texto-no-limite! (:texto-descricao mp) :texto-descricao)))))

(defn reordenar-item->dominio
  "Path-params `:id` (sessao) + `:item-id` + corpo JSON {nova-ordem, lock-version} -> mapa de dominio p/
  controllers/reordenar-item-pauta. Coage os uuids (malformado -> 400) e exige `nova-ordem`/`lock-version`
  inteiros 0..int4 (CAS otimista + sort hint; ausente/fora do range -> 400, NUNCA 500). Le as chaves STRING."
  [sessao-id-str item-id-str json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON {nova-ordem, lock-version}" {:campo :corpo}))
  {:sessao-id    (->uuid sessao-id-str :id)
   :item-id      (->uuid item-id-str :item-id)
   :nova-ordem   (->int4 (get json-params "nova-ordem") :nova-ordem)
   :lock-version (->int4 (get json-params "lock-version") :lock-version)})

(defn remover-item->dominio
  "Path-params `:id` (sessao) + `:item-id` + corpo JSON {tipo, justificativa?, lock-version} -> mapa de dominio
  p/ controllers/remover-item-pauta. `tipo` ∈ {exclusao, retirada_pedido_autor} (fail-closed -> 400, nunca o
  CHECK do banco -> 500); `justificativa` opcional (se presente, nao pode ser vazia/branca); `lock-version`
  inteiro 0..int4 (CAS). Coage os uuids. Le as chaves STRING."
  [sessao-id-str item-id-str json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON {tipo, lock-version}" {:campo :corpo}))
  (let [tipo (get json-params "tipo")
        just (get json-params "justificativa")]
    (when-not (contains? logic/tipos-remocao-pauta tipo)
      (invalido! "tipo de remocao invalido (so exclusao|retirada_pedido_autor)" {:campo :tipo}))
    (when (and (some? just) (or (not (string? just)) (str/blank? just)))
      (invalido! "justificativa, se presente, nao pode ser vazia" {:campo :justificativa}))
    (texto-no-limite! just :justificativa)
    (cond-> {:sessao-id    (->uuid sessao-id-str :id)
             :item-id      (->uuid item-id-str :item-id)
             :tipo         tipo
             :lock-version (->int4 (get json-params "lock-version") :lock-version)}
      (some? just) (assoc :justificativa just))))
