(ns oplenario.legislativo.adapters.in.contas
  "Gate de ENTRADA `json -> dominio` do julgamento das contas (ADR-0021 Parte B, §22.10 adapters/in). Corpo com chaves
  STRING (corpo-json); so' le o allowlist. Fail-closed -> 400. A Casa e quem registra vem do `ator`, nunca do corpo."
  (:require [clojure.string :as str]
            [oplenario.legislativo.logic.contas :as contas])
  (:import (java.time LocalDate)
           (java.util UUID)))

(set! *warn-on-reflection* true)

(defn- invalido! [msg campo] (throw (ex-info msg {:tipo :validacao/invalido :campo campo})))

(defn- objeto! [json]
  (when-not (map? json) (invalido! "corpo deve ser objeto JSON" :corpo))
  json)

(defn ->uuid [s campo]
  (try (UUID/fromString ^String s)
       (catch Exception _ (invalido! (str (name campo) " invalido") campo))))

(defn id-do-path
  "O uuid do path-param `k`, ou nil (malformado = inexistente: a borda responde 404, nunca 500)."
  [req k]
  (try (UUID/fromString ^String (get-in req [:path-params k])) (catch Exception _ nil)))

(defn- data [v campo]
  (try (LocalDate/parse ^String v)
       (catch Exception _ (invalido! (str (name campo) " deve ser AAAA-MM-DD") campo))))

(defn- texto
  "O texto aparado do campo (nil se ausente/em branco); passou do teto -> 400."
  [json k teto]
  (let [v (get json k)]
    (when (and (some? v) (not (string? v))) (invalido! (str k " deve ser texto") (keyword k)))
    (let [t (some-> v str/trim)]
      (when (and t (> (count t) teto)) (invalido! (str k " tem ate' " teto " caracteres") (keyword k)))
      (when-not (str/blank? t) t))))

(defn registro->dominio
  "{tipo, exercicio, responsavel, recebida-em, processo-tce?, parecer-previo?, comissao-autora-id?, situacao-tce?} ->
  dominio. Governo exige parecer previo e comissao autora; a Mesa nao leva comissao (nao ha' PDL)."
  [json]
  (let [json (objeto! json)
        tipo (get json "tipo")
        exercicio (get json "exercicio")
        responsavel (texto json "responsavel" 200)
        recebida (get json "recebida-em")
        parecer (get json "parecer-previo")
        comissao (get json "comissao-autora-id")]
    (when-not (contains? contas/tipos tipo) (invalido! "tipo invalido" :tipo))
    (when-not (and (int? exercicio) (<= 1990 exercicio 9999)) (invalido! "exercicio invalido" :exercicio))
    (when-not responsavel (invalido! "informe o responsavel" :responsavel))
    (when-not (string? recebida) (invalido! "informe recebida-em (AAAA-MM-DD)" :recebida-em))
    (when (and (some? parecer) (not (contains? contas/pareceres parecer))) (invalido! "parecer-previo invalido" :parecer-previo))
    (when (= "governo_prefeito" tipo)
      (when (nil? parecer) (invalido! "as contas do Prefeito exigem o parecer previo do TCE" :parecer-previo))
      (when-not (string? comissao) (invalido! "informe a comissao autora do projeto de decreto legislativo" :comissao-autora-id)))
    (cond-> {:tipo tipo :exercicio (long exercicio) :responsavel responsavel
             :recebida-em (data recebida :recebida-em)
             :processo-tce (texto json "processo-tce" 80)
             :parecer-previo parecer
             :situacao-tce (texto json "situacao-tce" 500)}
      (= "governo_prefeito" tipo) (assoc :comissao-autora-id (->uuid comissao :comissao-autora-id)))))

(defn edicao->dominio
  "{processo-tce?, situacao-tce?} -> so' as chaves PRESENTES (null ou vazio limpa o campo)."
  [json]
  (let [json (objeto! json)]
    (when-not (or (contains? json "processo-tce") (contains? json "situacao-tce"))
      (invalido! "nada a alterar: informe processo-tce ou situacao-tce" :corpo))
    (cond-> {}
      (contains? json "processo-tce") (assoc :processo-tce (texto json "processo-tce" 80))
      (contains? json "situacao-tce") (assoc :situacao-tce (texto json "situacao-tce" 500)))))

(defn notificacao->dominio
  "{notificado-em, meio} -> {:notificado-em LocalDate :meio}."
  [json]
  (let [json (objeto! json)
        em (get json "notificado-em")
        meio (texto json "meio" 200)]
    (when-not (string? em) (invalido! "informe notificado-em (AAAA-MM-DD)" :notificado-em))
    (when-not meio (invalido! "informe o meio da notificacao" :meio))
    {:notificado-em (data em :notificado-em) :meio meio}))

(defn tipo-do-documento
  "?tipo= do upload: um dos tipos de documento da prestacao (obrigatorio)."
  [query]
  (let [t (get query :tipo)]
    (when-not (contains? contas/tipos-documento t) (invalido! "tipo de documento invalido" :tipo))
    t))

(defn parametros->dominio
  "{prazo-defesa-dias, prazo-julgamento-dias} -> dominio (1..120 e 1..365, os CHECK da migration)."
  [json]
  (let [json (objeto! json)
        defesa (get json "prazo-defesa-dias")
        julgamento (get json "prazo-julgamento-dias")]
    (when-not (and (int? defesa) (<= 1 defesa 120)) (invalido! "prazo-defesa-dias entre 1 e 120" :prazo-defesa-dias))
    (when-not (and (int? julgamento) (<= 1 julgamento 365))
      (invalido! "prazo-julgamento-dias entre 1 e 365" :prazo-julgamento-dias))
    {:prazo-defesa-dias (long defesa) :prazo-julgamento-dias (long julgamento)}))
