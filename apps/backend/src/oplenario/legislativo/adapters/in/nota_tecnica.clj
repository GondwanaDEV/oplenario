(ns oplenario.legislativo.adapters.in.nota-tecnica
  "Gate de ENTRADA `json -> dominio` da decisao da secretaria sobre a nota tecnica (Faixa B / B.8, §22.10
  adapters/in). Corpo com chaves STRING (corpo-json); so' le o allowlist. Fail-closed -> 400. Quem decide vem do
  `ator`, nunca do corpo."
  (:require [clojure.string :as str]
            [oplenario.legislativo.logic :as logic]))

(set! *warn-on-reflection* true)

(def teto-texto 20000)

(defn- invalido! [msg campo] (throw (ex-info msg {:tipo :validacao/invalido :campo campo})))

(defn decisao->dominio
  "{\"desfecho\" \"aproveitada\"|\"descartada\", \"texto\"?} -> {:desfecho :texto?}. `texto` = o que a secretaria
  aproveitou, editado; ausente = o texto do agente, sem as marcas de citacao."
  [json]
  (when-not (map? json) (invalido! "corpo deve ser objeto JSON" :corpo))
  (let [desfecho (get json "desfecho")
        texto (some-> (get json "texto") str str/trim)]
    (when-not (contains? logic/desfechos-nota-tecnica desfecho)
      (invalido! "desfecho deve ser 'aproveitada' ou 'descartada'" :desfecho))
    (when (and texto (> (count texto) teto-texto))
      (invalido! (str "o texto aproveitado tem ate' " teto-texto " caracteres") :texto))
    (cond-> {:desfecho desfecho}
      (and (= "aproveitada" desfecho) (not (str/blank? texto))) (assoc :texto texto))))

(defn estado-da-fila
  "?estado=pendente|aproveitada|descartada|todas -> o estado (nil = todas). Padrao: pendente."
  [query]
  (let [e (get query :estado "pendente")]
    (cond (= "todas" e) nil
          (contains? #{"pendente" "aproveitada" "descartada"} e) e
          :else (invalido! "estado invalido" :estado))))
