(ns oplenario.paineis.adapters.out.tramitacao
  "Gate de SAIDA `models -> wire/out` do board de tramitacao (§22.10 adapters/out, ADR-0001, F7 Slice 2) —
  chamado SO pelo diplomat/. Projeta o read-model do dominio (itens do board + totais-por-estado, kebab)
  p/ a representacao externa (strings) e FILTRA o tenant (ente-id), que nunca vaza. Validada contra
  wire/out.TramitacaoBoardOut (drift de campo = bug de servidor -> 500, nunca resposta malformada)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.paineis.wire.out.tramitacao :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- item->wire [i]
  {:proposicao-id (->str (:proposicao-id i)) :tipo (:tipo i) :ano (:ano i) :sequencial (:sequencial i)
   :urn-lex (:urn-lex i) :ementa (:ementa i) :autor-tipo (:autor-tipo i) :autor-texto (:autor-texto i)
   :estado (:estado i) :transicionou-em (->str (:transicionou-em i))})

(defn- total-por-estado->wire
  "db-tramitacao/resumo devolve {:estado :n} (mesma forma reusada pelo dashboard da Mesa) — so' RENOMEIA
  `:n` -> `:total` pro contrato de saida (fatia 'truncamento-familia'), nenhuma outra transformacao."
  [linha]
  {:estado (:estado linha) :total (:n linha)})

(defn tramitacao-board->wire
  "Read-model do board {:itens [...] :totais-por-estado [{:estado :n}...]} -> TramitacaoBoardOut
  (validada). `totais-por-estado` reusa `db-tramitacao/resumo` (mesmo predicado da lista, sem teto — ver
  docstring de wire/out/TramitacaoBoardOut e components/repositorio/tramitacao-board)."
  [{:keys [itens totais-por-estado]}]
  (let [out {:itens (mapv item->wire itens)
             :totais-por-estado (mapv total-por-estado->wire totais-por-estado)}]
    (when-not (m/validate wire/TramitacaoBoardOut out)
      (throw (ex-info "projecao do board de tramitacao viola o contrato TramitacaoBoardOut (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/TramitacaoBoardOut out))})))
    out))
