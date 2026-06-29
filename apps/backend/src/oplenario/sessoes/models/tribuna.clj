(ns oplenario.sessoes.models.tribuna
  "Representacao INTERNA (dominio) da TRIBUNA (§22.6 eixo F) — Malli (§22.10 models/). F4.5a: InscricaoOrador =
  camada de INTENCAO (origem_inscricao discrimina os 4 caminhos; situacao inscrita->desistencia). Enums de
  sessoes.logic (fonte unica; os CHECK da mig 0032 espelham). `vereador-id`/`proposicao-ref-id` sao forward-ref
  a outros schemas (uuid, sem FK, §22.10)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.sessoes.logic :as logic]))

(defn- enum-de [s] (into [:enum] (sort s)))

(def InscricaoOrador
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:sessao-id :uuid]
   [:vereador-id :uuid]
   [:origem-inscricao (enum-de logic/origens-inscricao)]
   [:fase (enum-de logic/fases-pauta)]
   [:proposicao-ref-id {:optional true} [:maybe :uuid]]
   [:estado (enum-de logic/estados-inscricao)]
   [:ordem :int]
   [:lock-version :int]])

(def FalaExecutada
  "A fala efetivamente ocorrida (F4.5b). `inscricao-id`/`fala-pai-id` nullable; `tempo-...` so apos encerrar."
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:sessao-id :uuid]
   [:inscricao-id {:optional true} [:maybe :uuid]]
   [:orador-id :uuid]
   [:tipo-fala (enum-de logic/tipos-fala)]
   [:fala-pai-id {:optional true} [:maybe :uuid]]
   [:fase (enum-de logic/fases-pauta)]
   [:proposicao-ref-id {:optional true} [:maybe :uuid]]
   [:iniciou-em km/Instante]
   [:encerrou-em {:optional true} [:maybe km/Instante]]
   [:tempo-efetivamente-usado-segundos {:optional true} [:maybe :int]]
   [:lock-version :int]])

(def FalaCronometroEvento
  "Evento append-only do cronometro da fala (F4.5b). `segundos-adicionais` so p/ tempo_adicional_concedido."
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:fala-id :uuid]
   [:tipo (enum-de logic/tipos-evento-cronometro)]
   [:ocorrido-em km/Instante]
   [:segundos-adicionais {:optional true} [:maybe :int]]])
