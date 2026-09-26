(ns oplenario.sessoes.adapters.in.ata
  "Gate de ENTRADA `json -> dominio` da publicacao da ATA (Faixa A / A.6, §22.10 adapters/in). Corpo com chaves
  STRING (corpo-json); so' le o allowlist. Fail-closed -> 400, nunca 500 do CHECK do banco. Quem publica vem do
  `ator`, nunca do corpo."
  (:require [clojure.string :as str]
            [oplenario.sessoes.logic :as logic])
  (:import (java.util UUID)))

(set! *warn-on-reflection* true)

(defn- invalido! [msg campo] (throw (ex-info msg {:tipo :validacao/invalido :campo campo})))

(defn publicar->dominio [json]
  (when-not (map? json) (invalido! "corpo deve ser objeto JSON" :corpo))
  (let [texto  (get json "texto")
        origem (get json "origem-redacao" "redigida_externamente")
        motivo (get json "motivo-retificacao")]
    (when-not (and (string? texto) (not (str/blank? texto)) (<= (count texto) logic/teto-texto-ata))
      (invalido! (str "o texto da ata e' obrigatorio (ate' " logic/teto-texto-ata " caracteres)") :texto))
    (when-not (contains? logic/origens-redacao-ata origem)
      (invalido! "origem de redacao desconhecida" :origem-redacao))
    (when (and (some? motivo) (not (and (string? motivo) (<= (count motivo) 2000))))
      (invalido! "motivo da retificacao invalido (ate' 2000 caracteres)" :motivo-retificacao))
    ;; A.6b: a ata que partiu do rascunho da IA diz QUAL rascunho (o controller confere que e' desta sessao)
    (let [rid (when (= "gerada_automaticamente" origem)
                (try (UUID/fromString ^String (get json "rascunho-id"))
                     (catch Exception _ (invalido! "ata gerada pela IA exige o rascunho-id de origem" :rascunho-id))))]
      (cond-> {:texto texto :origem-redacao origem :motivo-retificacao (some-> motivo str/trim not-empty)}
        rid (assoc :rascunho-id rid)))))
