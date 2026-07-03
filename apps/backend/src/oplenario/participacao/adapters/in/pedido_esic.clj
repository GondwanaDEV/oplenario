(ns oplenario.participacao.adapters.in.pedido-esic
  "Gate de ENTRADA `wire/in -> models` do pedido e-SIC (§22.10 adapters/in, ADR-0001) — chamado SO pelo
  diplomat/. Valida e coage a representacao externa (JSON: strings) p/ o dominio, defendendo a borda
  (fail-closed -> 400 via ex-info :validacao/invalido). ALLOWLIST estrita (so assunto+descricao): o
  solicitante e o recibo sao INJETADOS do ator/relogio na borda, NUNCA vem do corpo (anti-forge). Os
  path-params (:id do pedido, :ente da rota publica) coagem a UUID fail-closed — um :ente malformado NUNCA
  vaza cross-tenant (400, nunca 500 nem tenant errado)."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.participacao.wire.in.pedido-esic :as wire])
  (:import (java.util UUID)))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- so-esperados [m campos]
  (reduce (fn [acc k] (cond-> acc (contains? m k) (assoc (keyword k) (get m k)))) {} campos))

(defn ->uuid
  "String -> UUID; malformado/ausente = requisicao invalida (:validacao/invalido -> 400), nunca 500.
  Guarda nil/branco ANTES de UUID/fromString (que lanca NPE — nao IllegalArgumentException — em nil):
  mantem TODO caller (id-param->uuid, ente-param->uuid) fail-closed a 400."
  [s campo]
  (when (str/blank? s) (invalido! "uuid ausente" {:campo campo}))
  (try (UUID/fromString s) (catch IllegalArgumentException _ (invalido! "uuid invalido" {:campo campo}))))

(defn id-param->uuid
  "Path-param :id (string) -> UUID do pedido (fail-closed -> 400)."
  [s] (->uuid s :id))

(defn ente-param->uuid
  "Path-param :ente (string) da rota PUBLICA -> UUID do ente. Malformado/ausente -> 400 fail-closed (NUNCA
  deixa um :ente invalido vazar/abrir tenant errado). Seam `resolver-ente-publico` do host: V1 identifica a
  Casa pelo UUID no segmento de path; slug humano e' refino futuro. A RLS (com-tenant* com este ente-id) e'
  o que isola a fronteira de tenant da rota sem-ator."
  [s]
  (when (str/blank? s) (invalido! "ente ausente na rota publica" {:campo :ente}))
  (->uuid s :ente))

(def ^:private campos ["assunto" "descricao"])

(defn coagir-pedido
  "Corpo JSON {assunto, descricao} (chaves STRING) -> mapa de dominio {:assunto :descricao}. ALLOWLIST
  (so-esperados) descarta qualquer campo forjado (estado, solicitante); o schema CLOSED + os nao-vazios
  espelham os CHECK da mig 0039. created-by/solicitante/recibo NAO entram aqui — injetados na borda."
  [json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON {assunto, descricao}" {:campo :corpo}))
  (let [mp (so-esperados json-params campos)]
    (when-let [erros (m/explain wire/PedidoEsicIn mp)]
      (invalido! "corpo de pedido e-SIC invalido" {:campos (keys (me/humanize erros))}))
    ;; espelha na borda os CHECK length(trim(x))>0 da mig 0039 (Malli :min 1 nao barra espacos em branco).
    (when (str/blank? (:assunto mp)) (invalido! "assunto obrigatorio nao pode ser vazio" {:campo :assunto}))
    (when (str/blank? (:descricao mp)) (invalido! "descricao obrigatoria nao pode ser vazia" {:campo :descricao}))
    {:assunto (:assunto mp) :descricao (:descricao mp)}))
