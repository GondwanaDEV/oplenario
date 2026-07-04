(ns oplenario.paineis.adapters.out.tramitacao
  "Gate de SAIDA `models -> wire/out` do board de tramitacao (§22.10 adapters/out, ADR-0001, F7 Slice 2) —
  chamado SO pelo diplomat/. Projeta o read-model do dominio (itens do board, kebab) p/ a representacao
  externa (strings) e FILTRA o tenant (ente-id), que nunca vaza. Validada contra wire/out.TramitacaoBoardOut
  (drift de campo = bug de servidor -> 500, nunca resposta malformada)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.paineis.wire.out.tramitacao :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- item->wire [i]
  {:proposicao-id (->str (:proposicao-id i)) :tipo (:tipo i) :ano (:ano i) :sequencial (:sequencial i)
   :urn-lex (:urn-lex i) :ementa (:ementa i) :autor-tipo (:autor-tipo i) :autor-texto (:autor-texto i)
   :estado (:estado i) :transicionou-em (->str (:transicionou-em i))})

(defn tramitacao-board->wire
  "Read-model do board {:itens [...]} -> TramitacaoBoardOut (validada)."
  [itens]
  (let [out {:itens (mapv item->wire itens)}]
    (when-not (m/validate wire/TramitacaoBoardOut out)
      (throw (ex-info "projecao do board de tramitacao viola o contrato TramitacaoBoardOut (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/TramitacaoBoardOut out))})))
    out))
