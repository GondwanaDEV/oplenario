(ns oplenario.normas.adapters.in.versao
  "Gate de ENTRADA `json -> dominio` das normas (ADR-0011, §22.10 adapters/in). Corpo com chaves STRING (corpo-json);
  so' le o allowlist. Fail-closed -> 400. Quem envia e quem confere vem do `ator`, nunca do corpo."
  (:require [clojure.string :as str]
            [oplenario.normas.logic :as logic])
  (:import (java.time LocalDate)))

(set! *warn-on-reflection* true)

(def teto-texto "Uma LOM ou um Regimento inteiro cabe folgado; um codigo de leis inteiro, nao." 1500000)

(defn- invalido! [msg campo] (throw (ex-info msg {:tipo :validacao/invalido :campo campo})))

(defn- texto-opcional [json k teto]
  (let [v (get json k)]
    (when (some? v)
      (when-not (and (string? v) (<= (count v) teto)) (invalido! (str k " invalido") k))
      (not-empty (str/trim v)))))

(defn- data-opcional [json k]
  (when-let [v (texto-opcional json k 10)]
    (try (LocalDate/parse v) (catch Exception _ (invalido! (str k " deve ser AAAA-MM-DD") k)))))

(defn importacao->dominio
  "{especie titulo numero? data? consolidada-ate? fonte texto} -> o pedido de importacao."
  [json]
  (when-not (map? json) (invalido! "corpo deve ser objeto JSON" :corpo))
  (let [especie (get json "especie")
        titulo (texto-opcional json "titulo" 300)
        fonte (texto-opcional json "fonte" 500)
        texto (get json "texto")]
    (when-not (contains? logic/especies-da-casa especie) (invalido! "especie invalida" :especie))
    (when-not titulo (invalido! "titulo obrigatorio" :titulo))
    (when-not fonte (invalido! "diga de onde veio o texto (endereco ou 'enviado pela Casa')" :fonte))
    (when-not (and (string? texto) (not (str/blank? texto)) (<= (count texto) teto-texto))
      (invalido! "texto obrigatorio" :texto))
    {:especie especie :titulo titulo :fonte fonte :texto texto
     :numero (texto-opcional json "numero" 40)
     :data (data-opcional json "data")
     :consolidada-ate (data-opcional json "consolidada-ate")}))

(defn decisao->dominio
  "{decisao: publicar | descartar}"
  [json]
  (let [d (when (map? json) (get json "decisao"))]
    (when-not (#{"publicar" "descartar"} d) (invalido! "decisao deve ser publicar ou descartar" :decisao))
    d))
