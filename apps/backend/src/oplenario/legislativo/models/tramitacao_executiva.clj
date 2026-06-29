(ns oplenario.legislativo.models.tramitacao-executiva
  "Representacao INTERNA (dominio) da TRAMITACAO NO EXECUTIVO — Malli (§22.10 models/, F3.8a). Processo
  sancao/veto que evolui (state machine). Os enums vem de legislativo.logic (fonte unica; os CHECK da
  migration 0022 espelham). Carimbos via kernel.malli/Instante (timestamptz)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.legislativo.logic :as logic]))

(defn- enum-de [s] (into [:enum] (sort s)))

(def TramitacaoExecutiva
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:autografo-id :uuid]
   [:estado (enum-de logic/estados-executivo)]
   ;; preenchidos quando ha veto
   [:veto-tipo {:optional true} [:maybe (enum-de logic/tipos-veto)]]
   [:veto-razoes {:optional true} [:maybe :string]]
   [:veto-votacao-id {:optional true} [:maybe :uuid]]
   [:respondido-em {:optional true} [:maybe km/Instante]]
   [:apreciado-em {:optional true} [:maybe km/Instante]]
   [:lock-version :int]])
