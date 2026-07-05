(ns oplenario.legislativo.adapters.in.proposicao
  "Gate de ENTRADA `wire/in -> models` da leitura de proposicoes (§22.10 adapters/in, ADR-0001, Onda B Slice
  1). Chamado SO pelo diplomat/. Coage os query-params de GET /legislativo/proposicoes: cada filtro de
  CONTEUDO e' OPCIONAL e TOLERANTE ao valor (ex.: tipo/estado desconhecidos so' nao casam nenhuma linha, nao
  sao 400 — mesmo racional de transparencia/adapters/in/portal/filtro-legislacao); ja' pagina/tamanho/
  ordenacao tem DEFAULT quando AUSENTES mas REJEITAM (400) quando PRESENTES e invalidos — nunca absorvidos
  em silencio, porque mudam o contrato de paginacao que o FE depende."
  (:require [clojure.string :as str])
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
