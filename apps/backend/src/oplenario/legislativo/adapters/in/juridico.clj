(ns oplenario.legislativo.adapters.in.juridico
  "Gate de ENTRADA `json -> dominio` do caminho da comissao e do parecer juridico (ADR-0019 fatia 1, §22.10 adapters/in).
  Corpo com chaves STRING (corpo-json); so' le o allowlist. Fail-closed -> 400. Quem pede, quem assina e a Casa vem do
  `ator`, nunca do corpo (nome, OAB e qualificacao do signatario saem do perfil juridico, nao do JSON)."
  (:require [clojure.string :as str]
            [oplenario.legislativo.logic :as logic])
  (:import (java.time LocalDate)
           (java.time.format DateTimeParseException)
           (java.util UUID)))

(set! *warn-on-reflection* true)

(defn- invalido! [msg campo] (throw (ex-info msg {:tipo :validacao/invalido :campo campo})))

(defn- objeto! [json]
  (when-not (map? json) (invalido! "corpo deve ser objeto JSON" :corpo))
  json)

(defn- ->uuid [s campo]
  (try (UUID/fromString ^String s)
       (catch Exception _ (invalido! (str campo " invalido") (keyword campo)))))

(def assunto-padrao "Análise jurídica da matéria")

(defn pedido->dominio
  "{\"proposicao-id\"? \"assunto\"? \"prazo\"? \"em-nome-de\"?} -> {:proposicao-id? :assunto :prazo? :em-nome-de?}.
  `assunto` e' obrigatorio na consulta avulsa (sem materia); com materia, o padrao e' a analise da materia."
  [json]
  (let [json (objeto! json)
        pid (get json "proposicao-id")
        assunto (some-> (get json "assunto") str str/trim)
        prazo (get json "prazo")
        nome (some-> (get json "em-nome-de") str str/trim)
        assunto (cond (not (str/blank? assunto)) assunto
                      (some? pid) assunto-padrao
                      :else (invalido! "a consulta avulsa precisa de um assunto" :assunto))]
    (when-not (<= 5 (count assunto) 300) (invalido! "o assunto tem de 5 a 300 caracteres" :assunto))
    (when (and nome (> (count nome) 80)) (invalido! "em-nome-de tem ate' 80 caracteres" :em-nome-de))
    (cond-> {:assunto assunto}
      (some? pid) (assoc :proposicao-id (->uuid pid "proposicao-id"))
      (some? prazo) (assoc :prazo (try (LocalDate/parse ^String prazo)
                                       (catch DateTimeParseException _ (invalido! "prazo deve ser AAAA-MM-DD" :prazo))
                                       (catch ClassCastException _ (invalido! "prazo deve ser AAAA-MM-DD" :prazo))))
      (not (str/blank? nome)) (assoc :em-nome-de nome))))

(defn pedido-do-relator->dominio
  "{\"assunto\"?} -> {:assunto}. O relator pede sobre a materia que relata; sem assunto, o padrao."
  [json]
  (let [json (if (nil? json) {} (objeto! json))
        assunto (some-> (get json "assunto") str str/trim)
        assunto (if (str/blank? assunto) assunto-padrao assunto)]
    (when-not (<= 5 (count assunto) 300) (invalido! "o assunto tem de 5 a 300 caracteres" :assunto))
    {:assunto assunto}))

(defn texto->dominio
  "{\"relatorio\" \"fundamentacao\" \"conclusao\"?} -> {:relatorio :fundamentacao :conclusao?}. O rascunho aceita texto
  incompleto (a assinatura e' que exige tudo)."
  [json]
  (let [json (objeto! json)
        relatorio (str (get json "relatorio"))
        fundamentacao (str (get json "fundamentacao"))
        conclusao (get json "conclusao")]
    (when (> (count relatorio) 30000) (invalido! "relatorio tem ate' 30000 caracteres" :relatorio))
    (when (> (count fundamentacao) 60000) (invalido! "fundamentacao tem ate' 60000 caracteres" :fundamentacao))
    (when (and (some? conclusao) (not (contains? logic/conclusoes-parecer-juridico conclusao)))
      (invalido! "conclusao invalida" :conclusao))
    {:relatorio relatorio :fundamentacao fundamentacao :conclusao conclusao}))

(defn estado-da-fila
  "?estado=pendente|atendido|cancelado|todos -> o estado (nil = todos). Padrao: pendente."
  [query]
  (let [e (get query :estado "pendente")]
    (cond (= "todos" e) nil
          (contains? logic/estados-pedido-juridico e) e
          :else (invalido! "estado invalido" :estado))))

(defn comissoes->dominio
  "{\"comissoes\" [{\"comissao-id\" \"relator-id\"?}]} (1 a 10, sem repetir comissao) ->
  [{:comissao-id :relator-id?}]."
  [json]
  (let [cs (get (objeto! json) "comissoes")]
    (when-not (and (sequential? cs) (<= 1 (count cs) 10)) (invalido! "informe de 1 a 10 comissoes" :comissoes))
    (let [itens (mapv (fn [c]
                        (when-not (map? c) (invalido! "comissao deve ser objeto" :comissoes))
                        (cond-> {:comissao-id (->uuid (get c "comissao-id") "comissao-id")}
                          (some? (get c "relator-id")) (assoc :relator-id (->uuid (get c "relator-id") "relator-id"))))
                      cs)]
      (when-not (= (count itens) (count (distinct (map :comissao-id itens))))
        (invalido! "comissao repetida" :comissoes))
      itens)))

(defn relator->dominio
  "{\"relator-id\"} -> uuid."
  [json]
  (let [rid (get (objeto! json) "relator-id")]
    (when (nil? rid) (invalido! "relator-id e' obrigatorio" :relator-id))
    (->uuid rid "relator-id")))
