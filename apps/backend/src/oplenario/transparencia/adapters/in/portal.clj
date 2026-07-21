(ns oplenario.transparencia.adapters.in.portal
  "Gate de ENTRADA `wire/in -> models` do portal (§22.10 adapters/in, ADR-0001) — chamado SO pelo diplomat/.
  TODAS as rotas desta fatia sao PUBLICAS (sem auth, sem corpo) — so' coagem path-params a UUID fail-closed
  (:validacao/invalido -> 400). Mesmo seam `ente-param->uuid` de participacao (V1: :ente do path = UUID cru;
  slug humano e' refino futuro)."
  (:require [clojure.string :as str])
  (:import (java.util UUID)))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn ->uuid
  "String -> UUID; malformado/ausente = requisicao invalida (:validacao/invalido -> 400), nunca 500."
  [s campo]
  (when (str/blank? s) (invalido! "uuid ausente" {:campo campo}))
  (try (UUID/fromString s) (catch IllegalArgumentException _ (invalido! "uuid invalido" {:campo campo}))))

(defn ente-param->uuid
  "Path-param :ente (rota PUBLICA) -> UUID do ente. Malformado/ausente -> 400 fail-closed (NUNCA vaza
  cross-tenant). A RLS (com-tenant* com este ente-id) e' o que isola a fronteira de tenant da rota sem-ator."
  [s]
  (when (str/blank? s) (invalido! "ente ausente na rota publica" {:campo :ente}))
  (->uuid s :ente))

(defn proposicao-param->uuid [s] (->uuid s :proposicao-id))
(defn norma-param->uuid [s] (->uuid s :norma-id))

(defn vereador-param->uuid
  "Path-param :vereador_id do perfil publico (Onda E fatia 2) -> UUID. Malformado -> 400, NUNCA 404: um 404
  para lixo sintatico afirmaria 'este vereador nao existe nesta Casa' sobre algo que nem e' identificador."
  [s]
  (->uuid s :vereador-id))

;; ---------- F6c Slice 3: coercao dos query-params do acervo de legislacao (todos OPCIONAIS) ----------

(def ^:private especie-max 64)       ; teto de tamanho da especie (anti-abuso; qualquer especie real cabe folgado)
(def ^:private inteiro-max-chars 11) ; int4 = ate 10 digitos + sinal; acima disso nao e' numero valido

(defn- ->single
  "Um query-param do Pedestal e' String (uma ocorrencia) ou VETOR de Strings (repetido na URL: ?ano=1&ano=2).
  Repetido e' AMBIGUO p/ um filtro (esp. o known-item tipo+ano+numero) -> :validacao/invalido (400): nunca
  escolhe 'primeiro/ultimo' em silencio, e nunca deixa o vetor chegar a str/trim (ClassCastException -> 500,
  em rota publica). Ausente -> nil."
  [s campo]
  (cond (nil? s)     nil
        (string? s)  s
        :else        (invalido! "parametro repetido" {:campo campo})))

(defn query-especie
  "Query-param :tipo (especie) OPCIONAL -> string trimada, MINUSCULA e nao-vazia, ou nil (ausente/blank).
  Case-insensitive: o vocabulario (lei/resolucao/lei_complementar/...) e' armazenado minusculo — 'Lei' casa
  'lei'. Tolerante ao VALOR (especie desconhecida so' nao casa nenhuma norma — nao e' 400), mas com teto de
  tamanho (> especie-max -> 400, nunca predicado gigante) e param repetido -> 400 (via ->single)."
  [s]
  (let [t (some-> (->single s :tipo) str/trim)]
    (when-not (str/blank? t)
      (when (> (count t) especie-max) (invalido! "especie grande demais" {:campo :tipo}))
      (str/lower-case t))))

(defn query-inteiro
  "Query-param inteiro OPCIONAL (ano/numero) -> Integer, ou nil (ausente/blank). Integer/parseInt casa o tipo
  REAL da coluna (int4): nao-inteiro, fora do range int4, ou string longa demais (> inteiro-max-chars) ->
  :validacao/invalido (400 fail-closed), nunca 500 nem vazio-silencioso. Param repetido -> 400 (via ->single)."
  [s campo]
  (let [t (some-> (->single s campo) str/trim)]
    (when-not (str/blank? t)
      (when (> (count t) inteiro-max-chars) (invalido! "inteiro grande demais" {:campo campo}))
      (try (Integer/parseInt t)
           (catch NumberFormatException _ (invalido! "inteiro invalido" {:campo campo}))))))

(defn filtro-legislacao
  "Constroi o filtro {:tipo :ano :numero} do acervo a partir dos `query-params` (mapa keyword->string do
  Pedestal; nil se ausentes). Cada chave e' coagida e OPCIONAL (nil quando ausente)."
  [query-params]
  {:tipo   (query-especie (:tipo query-params))
   :ano    (query-inteiro (:ano query-params) :ano)
   :numero (query-inteiro (:numero query-params) :numero)})
