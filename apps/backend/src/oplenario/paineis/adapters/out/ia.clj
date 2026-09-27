(ns oplenario.paineis.adapters.out.ia
  "Gate de SAIDA do painel da IA da Casa (B.9). Recebe o orcamento (core), o consumo (satelite, ja' em chaves keyword;
  nil = IA fora) e as contagens de desfecho (core); projeta campo a campo e valida contra wire/out."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.paineis.wire.out.ia :as wire]))

(set! *warn-on-reflection* true)

(defn- validado [out]
  (when-not (m/validate wire/PainelIAOut out)
    (throw (ex-info "painel da IA viola o contrato (bug de servidor)" {:erros (me/humanize (m/explain wire/PainelIAOut out))})))
  out)

(defn- decimal [x] (some-> x bigdec .toPlainString))

(defn- instante [x] (some-> x (#(if (instance? java.util.Date %) (.toInstant ^java.util.Date %) %)) str))

(defn- conta [m k] (long (get m k 0)))

(defn painel->wire [{:keys [mes orcamento consumo notas propostas]}]
  (validado
   {:mes mes
    :orcamento (when orcamento
                 {:mensal (decimal (:mensal orcamento)) :teto-duro (decimal (:teto-duro orcamento))
                  :moeda (:moeda orcamento) :definido-em (instante (:definido-em orcamento))})
    :consumo-disponivel (some? consumo)
    :estado (:estado consumo)
    :gasto (some-> (:gasto consumo) decimal)
    :moeda (:moeda consumo)
    :parcial (boolean (:parcial consumo))
    :execucoes (long (or (:execucoes consumo) 0))
    :por-capacidade (mapv (fn [o] {:operacao (str (:operacao o)) :execucoes (conta o :execucoes)
                                   :indisponiveis (conta o :indisponiveis) :custo (decimal (or (:custo o) 0))
                                   :aprovados (conta o :aprovados) :editados (conta o :editados)
                                   :descartados (conta o :descartados) :erros-reportados (conta o :erros-reportados)})
                          (:por-operacao consumo))
    :notas-tecnicas {:pendentes (conta notas "pendente") :aproveitadas (conta notas "aproveitada")
                     :descartadas (conta notas "descartada")}
    :propostas {:aguardando (+ (conta propostas "aguardando") (conta propostas "executando"))
                :confirmadas (conta propostas "confirmada") :recusadas (conta propostas "recusada")
                :expiradas (conta propostas "expirada")}}))
