(ns oplenario.legislativo.adapters.in.resumo
  "Gate de ENTRADA `json -> dominio` da publicacao do resumo cidadao (Faixa A / A.8, §22.10 adapters/in). Corpo com
  chaves STRING (corpo-json); so' le o allowlist. Fail-closed -> 400. Quem publica vem do `ator`, nunca do corpo."
  (:require [clojure.string :as str]
            [oplenario.legislativo.logic :as logic])
  (:import (java.util UUID)))

(set! *warn-on-reflection* true)

(defn- invalido! [msg campo] (throw (ex-info msg {:tipo :validacao/invalido :campo campo})))

(defn publicar->dominio
  "{\"texto\" \"rascunho-id\"?} -> {:texto :rascunho-id?}. O texto chega aparado; `rascunho-id` diz de qual rascunho
  da IA a versao partiu (o repositorio confere que e' um rascunho pronto desta proposicao)."
  [json]
  (when-not (map? json) (invalido! "corpo deve ser objeto JSON" :corpo))
  (let [texto (some-> (get json "texto") str/trim)
        rid   (get json "rascunho-id")]
    (when-not (and (string? texto) (not (str/blank? texto)) (<= (count texto) logic/teto-texto-resumo))
      (invalido! (str "o texto do resumo e' obrigatorio (ate' " logic/teto-texto-resumo " caracteres)") :texto))
    (cond-> {:texto texto}
      (some? rid) (assoc :rascunho-id (try (UUID/fromString ^String rid)
                                           (catch Exception _ (invalido! "rascunho-id invalido" :rascunho-id)))))))
