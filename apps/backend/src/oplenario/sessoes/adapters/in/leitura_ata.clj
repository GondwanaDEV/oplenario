(ns oplenario.sessoes.adapters.in.leitura-ata
  "Gate de ENTRADA `json -> dominio` do registro da LEITURA DA ATA (Faixa A / A.7, §22.10 adapters/in). So' le o
  allowlist; fail-closed -> 400. Quem registra vem do `ator`, nunca do corpo."
  (:require [oplenario.sessoes.logic :as logic])
  (:import (java.util UUID)))

(set! *warn-on-reflection* true)

(defn- invalido! [msg campo] (throw (ex-info msg {:tipo :validacao/invalido :campo campo})))

(defn registrar->dominio [json]
  (when-not (map? json) (invalido! "corpo deve ser objeto JSON" :corpo))
  (let [modo (get json "modo") versao (get json "ata-versao")]
    (when-not (contains? logic/modos-leitura-ata modo) (invalido! "modo de leitura desconhecido" :modo))
    (when-not (and (integer? versao) (pos? versao) (<= versao Integer/MAX_VALUE))
      (invalido! "versao da ata invalida" :ata-versao))
    {:modo modo
     :ata-versao (int versao)
     :ata-sessao-id (try (UUID/fromString ^String (get json "ata-sessao-id"))
                         (catch Exception _ (invalido! "sessao da ata invalida" :ata-sessao-id)))}))
