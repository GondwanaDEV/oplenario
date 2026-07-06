(ns oplenario.legislativo.adapters.in.proposicao
  "Gate de ENTRADA `wire/in -> models` da proposicao (§22.10 adapters/in, ADR-0001). Chamado SO pelo
  diplomat/. Onda B Slice 1: coage os query-params de GET /legislativo/proposicoes — cada filtro de
  CONTEUDO e' OPCIONAL e TOLERANTE ao valor (ex.: tipo/estado desconhecidos so' nao casam nenhuma linha, nao
  sao 400 — mesmo racional de transparencia/adapters/in/portal/filtro-legislacao); ja' pagina/tamanho/
  ordenacao tem DEFAULT quando AUSENTES mas REJEITAM (400) quando PRESENTES e invalidos — nunca absorvidos
  em silencio, porque mudam o contrato de paginacao que o FE depende. Onda B Slice 2: valida (fail-closed ->
  400) e COAGE o corpo JSON de POST/PATCH p/ o dominio (uuid), defendendo a borda; INJETA o que nao vem do
  corpo — `id` novo (criar) ou do path (editar), `created-by`/`updated-by` sempre do `ator` (§22.5)."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.legislativo.wire.in.proposicao :as wire])
  (:import (java.util UUID)))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- ->single
  "Um query-param do Pedestal e' String (uma ocorrencia) ou VETOR (repetido na URL) — repetido e' AMBIGUO
  p/ um filtro escalar -> 400 (nunca escolhe 'primeiro/ultimo' em silencio). Ausente -> nil."
  [s campo]
  (cond (nil? s) nil (string? s) s :else (invalido! "parametro repetido" {:campo campo})))

(def ^:private texto-max 200)
(def ^:private inteiro-max-chars 11)
(def ^:private tamanho-min 1)
(def ^:private tamanho-max 100)
(def ^:private tamanho-default 20)
(def ^:private pagina-min 1)
(def ^:private pagina-max 100000)
(def ^:private pagina-default 1)
(def ^:private ordenar-por-default "atualizado_em")
(def ^:private ordenar-por-valores #{"atualizado_em" "sequencial" "ano"})
(def ^:private ordenar-dir-default "desc")
(def ^:private ordenar-dir-valores #{"asc" "desc"})

(defn- query-texto [s campo]
  (let [t (some-> (->single s campo) str/trim)]
    (when-not (str/blank? t)
      (when (> (count t) texto-max) (invalido! "parametro grande demais" {:campo campo}))
      t)))

(defn- query-inteiro [s campo]
  (let [t (some-> (->single s campo) str/trim)]
    (when-not (str/blank? t)
      (when (> (count t) inteiro-max-chars) (invalido! "inteiro grande demais" {:campo campo}))
      (try (Integer/parseInt t)
           (catch NumberFormatException _ (invalido! "inteiro invalido" {:campo campo}))))))

(defn- query-uuid [s campo]
  (let [t (some-> (->single s campo) str/trim)]
    (when-not (str/blank? t)
      (try (UUID/fromString t) (catch IllegalArgumentException _ (invalido! "uuid invalido" {:campo campo}))))))

(defn- query-inteiro-em-faixa [s campo minimo maximo default]
  (if-let [n (query-inteiro s campo)]
    (do (when (or (< n minimo) (> n maximo)) (invalido! "fora da faixa permitida" {:campo campo :valor n}))
        n)
    default))

(defn- query-enum [s campo valores default]
  (if-let [t (query-texto s campo)]
    (do (when-not (contains? valores t) (invalido! "valor nao permitido" {:campo campo :valor t}))
        t)
    default))

(defn listar-proposicoes->dominio
  "query-params (mapa keyword->string|vetor do Pedestal) -> filtro+paginacao de dominio p/
  controllers/listar-proposicoes e db/proposicao.clj (listar/contar)."
  [query-params]
  {:busca       (query-texto (:busca query-params) :busca)
   :tipo        (query-texto (:tipo query-params) :tipo)
   :estado      (query-texto (:estado query-params) :estado)
   :autor-id    (query-uuid (:autor-id query-params) :autor-id)
   :ano         (query-inteiro (:ano query-params) :ano)
   :pagina      (query-inteiro-em-faixa (:pagina query-params) :pagina pagina-min pagina-max pagina-default)
   :tamanho     (query-inteiro-em-faixa (:tamanho query-params) :tamanho tamanho-min tamanho-max tamanho-default)
   :ordenar-por (query-enum (:ordenar-por query-params) :ordenar-por ordenar-por-valores ordenar-por-default)
   :ordenar-dir (query-enum (:ordenar-dir query-params) :ordenar-dir ordenar-dir-valores ordenar-dir-default)})

;; ---------- Onda B Slice 2: criar/editar (corpo JSON, nao query-params) ----------

(def ^:private campos-criar
  ["tipo" "ano" "ementa" "autor-tipo" "autor-id" "autor-texto" "objeto-indicacao" "destinatario-id"
   "destinatario-texto" "tipo-requerimento" "categoria-mocao" "texto"])
(def ^:private campos-editar
  ["lock-version" "ementa" "autor-tipo" "autor-id" "autor-texto" "objeto-indicacao" "destinatario-id"
   "destinatario-texto" "tipo-requerimento" "categoria-mocao" "texto"])

(defn- so-esperados
  "mapa STRING-keyed -> mapa keyword-keyed contendo SO os `campos` presentes (keyword ja internada)."
  [m campos]
  (reduce (fn [acc k] (cond-> acc (contains? m k) (assoc (keyword k) (get m k)))) {} campos))

(defn- ->uuid [s campo]
  (try (UUID/fromString s) (catch IllegalArgumentException _ (invalido! "uuid invalido" {:campo campo}))))

(defn- ->uuid? [s campo] (when (some? s) (->uuid s campo)))

(defn- validar! [schema m msg]
  (when-let [erros (m/explain schema m)]
    ;; guarda so os nomes-de-campo humanizados (NUNCA o payload cru — m/explain embute :value = vazaria dado).
    (invalido! msg {:campos (keys (me/humanize erros))})))

(defn criar-proposicao->dominio
  "Corpo externo (wire/in.CriarProposicao) + `ator` -> mapa de dominio p/ Repo/protocolar!. Gera `:id` e
  `:created-by`; `ente-id` vem do ator (o controller injeta) — nunca do cliente (§22.5)."
  [ator wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [m (so-esperados wire-in campos-criar)]
    (validar! wire/CriarProposicao m "corpo de criar proposicao invalido")
    {:id (random-uuid) :tipo (:tipo m) :ano (:ano m) :ementa (:ementa m)
     :autor-tipo (:autor-tipo m) :autor-id (->uuid? (:autor-id m) :autor-id) :autor-texto (:autor-texto m)
     :objeto-indicacao (:objeto-indicacao m)
     :destinatario-id (->uuid? (:destinatario-id m) :destinatario-id)
     :destinatario-texto (:destinatario-texto m) :tipo-requerimento (:tipo-requerimento m)
     :categoria-mocao (:categoria-mocao m) :texto (:texto m) :created-by (:identidade-id ator)}))

(defn editar-proposicao->dominio
  "Corpo (wire/in.EditarProposicao) + `ator` + `id` (path, ja' UUID) -> mapa de dominio p/
  Repo/editar-proposicao!. `id` = o id do path; `updated-by` = o ator — nunca do corpo (§22.5)."
  [ator id wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [m (so-esperados wire-in campos-editar)]
    (validar! wire/EditarProposicao m "corpo de editar proposicao invalido")
    {:id id :lock-version (:lock-version m) :ementa (:ementa m) :autor-tipo (:autor-tipo m)
     :autor-id (->uuid? (:autor-id m) :autor-id) :autor-texto (:autor-texto m)
     :objeto-indicacao (:objeto-indicacao m)
     :destinatario-id (->uuid? (:destinatario-id m) :destinatario-id)
     :destinatario-texto (:destinatario-texto m) :tipo-requerimento (:tipo-requerimento m)
     :categoria-mocao (:categoria-mocao m) :texto (:texto m) :updated-by (:identidade-id ator)}))
