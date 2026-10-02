(ns oplenario.comunicacao.adapters.in.comunicado
  "Gate de ENTRADA `json -> dominio` dos comunicados (ADR-0020, §22.10 adapters/in). Aceita o corpo da tela (chaves
  STRING, `corpo-json`) e a entrada do catalogo (chaves keyword, ids ja' UUID): normaliza as chaves, valida contra o
  wire (fail-closed -> 400) e coage ids/data. Quem envia, a Casa, o numero e a hora vem do ator e do servidor."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.comunicacao.wire.in :as wire])
  (:import (java.time LocalDate)
           (java.time.format DateTimeParseException)))

(set! *warn-on-reflection* true)

(defn- invalido! [msg campos] (throw (ex-info msg {:tipo :validacao/invalido :campos campos})))

(defn- chaves->kw
  "Chaves string -> keyword, recursivo nos mapas (o :closed do schema ve e recusa a chave forjada)."
  [x]
  (cond (map? x) (into {} (map (fn [[k v]] [(if (keyword? k) k (keyword (str k))) (chaves->kw v)])) x)
        (vector? x) (mapv chaves->kw x)
        (sequential? x) (mapv chaves->kw x)
        :else x))

(defn- ->uuid [v campo]
  (cond (nil? v) nil
        (uuid? v) v
        :else (or (parse-uuid (str v)) (invalido! "identificador invalido" [campo]))))

(defn- ->dia [s campo]
  (when (some? s)
    (when-not (re-matches #"^\d{4}-\d{2}-\d{2}$" s) (invalido! "data invalida (esperado AAAA-MM-DD)" [campo]))
    (try (LocalDate/parse s) (catch DateTimeParseException _ (invalido! "data invalida (esperado AAAA-MM-DD)" [campo])))))

(defn- texto! [s campo]
  (let [t (str/trim (str s))]
    (when (str/blank? t) (invalido! (str (name campo) " obrigatorio") [campo]))
    t))

(defn envio->dominio
  "{assunto corpo exige-ciencia? ciencia-ate? substitui-id? objeto? {tipo id} destinos [{tipo alvo-id?}]} -> o pedido
  de envio. `todos` nao leva alvo; os outros exigem."
  [json]
  (when-not (map? json) (invalido! "corpo deve ser objeto JSON" [:corpo]))
  (let [mm (chaves->kw json)]
    (when-let [erros (m/explain wire/EnviarComunicado mm)]
      (invalido! "corpo de comunicado invalido" (vec (keys (me/humanize erros)))))
    {:assunto (texto! (:assunto mm) :assunto)
     :corpo (let [c (str (:corpo mm))] (when (str/blank? c) (invalido! "corpo obrigatorio" [:corpo])) c)
     :exige-ciencia (boolean (:exige-ciencia mm))
     :ciencia-ate (->dia (:ciencia-ate mm) :ciencia-ate)
     :substitui-id (->uuid (:substitui-id mm) :substitui-id)
     :objeto (when-let [o (:objeto mm)] {:tipo (:tipo o) :id (->uuid (:id o) :objeto)})
     :destinos (mapv (fn [{:keys [tipo alvo-id]}]
                       (let [alvo (->uuid alvo-id :destinos)]
                         (cond (and (= "todos" tipo) alvo) (invalido! "todos os setores nao tem alvo" [:destinos])
                               (and (not= "todos" tipo) (nil? alvo)) (invalido! "destino sem alvo" [:destinos]))
                         {:tipo tipo :alvo-id alvo}))
                     (:destinos mm))}))

(defn id-do-path
  "O `:id` (ou outra chave) do caminho como UUID; invalido -> nil (a borda responde 404, nunca 500)."
  [req k]
  (parse-uuid (str (get-in req [:path-params k]))))

(defn escopo
  "`?escopo=casa` -> \"casa\"; ausente -> \"meus\"; outro valor -> 400."
  [req]
  (let [e (get-in req [:query-params :escopo])]
    (cond (or (nil? e) (= "" e) (= "meus" e)) "meus"
          (= "casa" e) "casa"
          :else (invalido! "escopo deve ser meus ou casa" [:escopo]))))
