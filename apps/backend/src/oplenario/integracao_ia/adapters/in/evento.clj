(ns oplenario.integracao-ia.adapters.in.evento
  "Gate de ENTRADA `json -> dominio` da caixa de entrada IA -> core (ADR-0008, §22.10 adapters/in). O envelope e
  o payload de cada (tipo, versao) conhecido sao validados campo a campo (fail-closed -> 400/422, nunca 500).
  So' le o ALLOWLIST de chaves; o corpo chega com chaves STRING (corpo-json)."
  (:require [clojure.string :as str]
            [oplenario.integracao-ia.logic :as logic])
  (:import (java.time Instant)
           (java.time.format DateTimeParseException)
           (java.util UUID)))

(set! *warn-on-reflection* true)

(defn- invalido! [msg campo] (throw (ex-info msg {:tipo :validacao/invalido :campo campo})))

(defn- uuid! [m k]
  (let [v (get m k)]
    (try (UUID/fromString ^String v) (catch Exception _ (invalido! "uuid invalido" k)))))

(defn- instante! [m k]
  (let [v (get m k)]
    (try (Instant/parse ^String v) (catch Exception _ (invalido! "instante invalido (ISO-8601)" k)))))

(defn- texto! [m k teto & {:keys [opcional]}]
  (let [v (get m k)]
    (cond (and opcional (nil? v)) nil
          (and (string? v) (not (str/blank? v)) (<= (count v) teto)) v
          :else (invalido! (str "texto invalido (1.." teto " caracteres)") k))))

(defn- inteiro! [m k minimo]
  (let [v (get m k)]
    (if (and (integer? v) (>= v minimo) (<= v Integer/MAX_VALUE)) (int v) (invalido! "inteiro invalido" k))))

(defn- numero! [m k minimo maximo]
  (let [v (get m k)]
    (if (and (number? v) (<= minimo v maximo)) (bigdec v) (invalido! "numero fora da faixa" k))))

(defn- payload-transcricao-concluida [p]
  {:sessao-id           (uuid! p "sessao-id")
   :segmento-id         (uuid! p "segmento-id")
   :transcricao-id      (uuid! p "transcricao-id")
   :versao              (inteiro! p "versao-transcricao" 1)
   :idioma              (texto! p "idioma" 16)
   :duracao-s           (numero! p "duracao-s" 0 1e7)
   :n-trechos           (inteiro! p "n-trechos" 0)
   :cobertura-atribuida (numero! p "cobertura-atribuida" 0 1)
   :modelo-asr          (texto! p "modelo-asr" 200)
   :modelo-diarizacao   (texto! p "modelo-diarizacao" 200 :opcional true)})

(defn- payload-transcricao-falhou [p]
  (let [cat (get p "categoria")]
    (when-not (contains? logic/categorias-falha cat) (invalido! "categoria de falha desconhecida" "categoria"))
    (when-not (boolean? (get p "retentavel")) (invalido! "retentavel deve ser booleano" "retentavel"))
    {:sessao-id   (uuid! p "sessao-id")
     :segmento-id (uuid! p "segmento-id")
     :categoria   cat
     :detalhe     (texto! p "detalhe" 2000)
     :retentavel  (get p "retentavel")}))

(def ^:private leitores
  {["TranscricaoConcluida" 1] payload-transcricao-concluida
   ["TranscricaoFalhou" 1]    payload-transcricao-falhou})

(defn evento->dominio
  "Corpo JSON -> {:tipo :versao :chave :ente-id :correlation-id :ocorrido-em :payload}. (tipo, versao)
  desconhecido -> `:validacao/evento-desconhecido` (422: a IA esta' a frente do core e o evento NAO e' aplicado)."
  [json]
  (when-not (map? json) (invalido! "corpo deve ser objeto JSON" "corpo"))
  (let [tipo   (texto! json "tipo" 80)
        versao (inteiro! json "versao" 1)
        ler    (get leitores [tipo versao])]
    (when-not ler
      (throw (ex-info "evento de integracao desconhecido" {:tipo :validacao/evento-desconhecido
                                                           :evento tipo :versao versao})))
    (when-not (map? (get json "payload")) (invalido! "payload deve ser objeto" "payload"))
    {:tipo           tipo
     :versao         versao
     :chave          (texto! json "chave" 200)
     :ente-id        (uuid! json "ente-id")
     :correlation-id (texto! json "correlation-id" 200 :opcional true)
     :ocorrido-em    (instante! json "ocorrido-em")
     :payload        (ler (get json "payload"))
     :bruto          (get json "payload")}))  ; o que CHEGOU, para o registro de auditoria da caixa de entrada

(defn cursor
  "Query `depois` e `limite` do feed -> {:depois long :limite int}. Ausentes = 0 e 100."
  [qp]
  (let [n (fn [k padrao]
            (if-let [s (get qp k)]
              (try (Long/parseLong s) (catch NumberFormatException _ (invalido! "numero invalido" (name k))))
              padrao))]
    {:depois (max 0 (n :depois 0)) :limite (logic/limite-do-feed (n :limite 100))}))

(defn id-de-caminho [s campo]
  (try (UUID/fromString s) (catch Exception _ (invalido! "uuid invalido" campo))))
