(ns oplenario.sessoes.adapters.in.publicacao-pauta
  "Gate de ENTRADA `wire/in -> models` de PUBLICAR A PAUTA e da REGRA DA PAUTA (ADR-0019 fatia 3; §22.10 adapters/in).
  Chamado SO pelo diplomat/. Valida FECHADO (campo a mais -> 400: a regra e' configuracao, e um campo ignorado em
  silencio e' a Casa achando que salvou o que nao salvou) e coage. O ator, a Casa e a que titulo se publica nunca vem
  do corpo."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.sessoes.wire.in :as wire])
  (:import (java.util UUID)))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- ->uuid [s campo]
  (try (UUID/fromString (str s)) (catch IllegalArgumentException _ (invalido! "uuid invalido" {:campo campo}))))

(defn- corpo->mapa
  "corpo-json (chaves STRING) -> mapa keyword; corpo ausente = {} (publicar sem justificativa nao precisa de corpo)."
  [json-params]
  (cond (nil? json-params) {}
        (map? json-params) (update-keys json-params keyword)
        :else (invalido! "corpo deve ser objeto JSON" {:campo :corpo})))

(defn publicar->dominio
  "Path-param `:id` (sessao) + corpo {justificativa?} -> {:sessao-id :justificativa}. Justificativa em branco = nil."
  [sessao-id-str json-params]
  (let [mp (corpo->mapa json-params)]
    (when-let [erros (m/explain wire/PublicarPauta mp)]
      (invalido! "corpo de publicar a pauta invalido" {:campos (keys (me/humanize erros))}))
    {:sessao-id (->uuid sessao-id-str :id)
     :justificativa (some-> (:justificativa mp) str/trim not-empty)}))

(defn regra->dominio
  "Corpo de PUT /regra-da-pauta {quem-publica, antecedencia-minima-horas?} -> dominio."
  [json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON {quem-publica, antecedencia-minima-horas}" {:campo :corpo}))
  (let [mp (update-keys json-params keyword)]
    (when-let [erros (m/explain wire/DefinirRegraPauta mp)]
      (invalido! "corpo da regra da pauta invalido" {:campos (keys (me/humanize erros))}))
    {:quem-publica (:quem-publica mp)
     :antecedencia-minima-horas (some-> (:antecedencia-minima-horas mp) long)}))
