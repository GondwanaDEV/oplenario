(ns oplenario.auditoria.adapters.out.trilha
  "Gate de SAIDA da trilha (ADR-0017): projeta campo a campo, trunca o IP, pseudonimiza o cidadao e valida contra
  wire/out (drift = 500, nunca resposta malformada). E o CSV da exportacao (RFC 4180, BOM, CRLF)."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.auditoria.logic :as logic]
            [oplenario.auditoria.wire.out.trilha :as wire]))

(set! *warn-on-reflection* true)

(defn- validado [schema nome out]
  (when-not (m/validate schema out)
    (throw (ex-info (str "projecao viola o contrato " nome " (bug de servidor)")
                    {:erros (me/humanize (m/explain schema out))})))
  out)

(defn ip-truncado
  "189.45.12.7 -> 189.45.x.x · 2001:db8:1:2::9 -> 2001:db8::x (ADR-0017 4b: guardado inteiro, mostrado truncado)."
  [ip]
  (when ip
    (if (str/includes? ip ":")
      (str (str/join ":" (take 2 (str/split ip #":"))) "::x")
      (let [p (str/split ip #"\.")] (if (= 4 (count p)) (str (p 0) "." (p 1) ".x.x") "x")))))

(defn- ator [r]
  {:tipo   (:ator-tipo r)
   :nome   (case (:ator-tipo r)
             "cidadao" (str "Cidadão " (:ator-pseudonimo r))
             (:ator-nome r))
   :papeis (vec (:papeis r))
   :via    (:via-agente r)})

(defn- decisao
  "A tentativa que chega aqui e' a que NAO tem desfecho (a leitura esconde a que tem): sai como `sem_desfecho`."
  [r]
  (if (= logic/iniciado (:decisao r)) "sem_desfecho" (:decisao r)))

(defn registro->wire [r]
  {:seq (long (:seq r)) :em (str (:ocorrido-em r)) :ator (ator r) :acao (:acao r) :classe (:classe r)
   :decisao (decisao r)
   :recurso (when (or (:recurso-tipo r) (:recurso-id r) (:rotulo r))
              {:tipo (:recurso-tipo r) :id (:recurso-id r) :rotulo (:rotulo r)})
   :campos (vec (:campos r)) :canal (:canal r) :ip (ip-truncado (:ip r))
   :selo (:selo r) :selo-anterior (:selo-anterior r)})

(defn trilha->wire [{:keys [escopo registros total total-da-casa operacao limite]}]
  (validado wire/TrilhaOut "TrilhaOut"
            {:escopo escopo :total (long total) :total-da-casa (some-> total-da-casa long)
             :registros (mapv registro->wire registros)
             :proximo (when (= (count registros) limite) (some-> (peek registros) :seq long))
             :operacao (when operacao
                         (mapv (fn [a] {:em (str (:em a)) :acao (:acao a) :operador (:operador-nome a) :selo (:selo a)})
                               operacao))}))

(defn- selos [xs] (mapv (fn [s] {:dia (str (:dia s)) :seq (long (:seq s)) :selo (:selo s)}) xs))

(defn integridade->wire [{:keys [integra total cabeca quebra-em selos-do-dia sem-desfecho]}]
  (validado wire/IntegridadeOut "IntegridadeOut"
            {:integra (boolean integra) :total (long total) :cabeca cabeca :quebra-em (some-> quebra-em long)
             :selos-do-dia (selos selos-do-dia)
             :sem-desfecho (long (or (:total sem-desfecho) 0))
             :primeiro-sem-desfecho (some-> (:primeiro sem-desfecho) long)}))

(defn selos-publicos->wire [xs]
  (validado wire/SelosPublicosOut "SelosPublicosOut" {:selos-do-dia (selos xs)}))

;; ---- CSV ----

(def colunas
  [["seq" :seq] ["quando" #(str (:ocorrido-em %))] ["quem" logic/quem] ["tipo_de_ator" :ator-tipo]
   ["papeis" #(str/join " " (:papeis %))] ["acao" :acao] ["classe" :classe]
   ;; a tentativa sem desfecho sai em palavras: o ato pode ter acontecido e o registro dele nao foi gravado
   ["decisao" #(if (= logic/iniciado (:decisao %)) "sem desfecho registrado" (:decisao %))]
   ["recurso_tipo" :recurso-tipo] ["recurso_id" :recurso-id] ["rotulo" :rotulo]
   ["campos" #(str/join " " (:campos %))] ["canal" :canal] ["ip" #(ip-truncado (:ip %))]
   ["selo_anterior" :selo-anterior] ["selo" :selo]])

(defn- celula [v]
  (let [s (if (nil? v) "" (str v))]
    (if (re-find #"[\",\r\n]" s) (str "\"" (str/replace s "\"" "\"\"") "\"") s)))

(defn ->csv [registros]
  (let [linha #(str (str/join "," %) "\r\n")]
    (apply str "﻿" (linha (map first colunas))
           (map (fn [r] (linha (map (fn [[_ f]] (celula (f r))) colunas))) registros))))
