(ns oplenario.sessoes.models.presenca
  "Representacao INTERNA (dominio) da PRESENCA (§22.6 eixo C, F4.3a) — Malli (§22.10 models/). PresencaEvento
  (fato append-only com instante de dominio `ocorrido-em`) e JustificativaAusencia (ato apartado com state
  machine). Enums de sessoes.logic (fonte unica; os CHECK da mig 0029 espelham). `vereador-id` e' forward-ref
  a cadastros (uuid, sem FK, §22.10)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.sessoes.logic :as logic]))

(defn- enum-de [s] (into [:enum] (sort s)))

(def PresencaEvento
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:sessao-id :uuid]
   [:vereador-id :uuid]
   [:tipo (enum-de logic/tipos-evento-presenca)]
   [:modalidade (enum-de logic/modalidades-presenca)]
   [:fonte (enum-de logic/fontes-presenca)]
   [:ocorrido-em km/Instante]])

(def JustificativaAusencia
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:sessao-id :uuid]
   [:vereador-id :uuid]
   [:estado (enum-de logic/estados-justificativa)]
   [:motivo [:string {:min 1}]]
   [:lock-version :int]
   [:decidido-por {:optional true} [:maybe :uuid]]
   [:decidido-em {:optional true} [:maybe km/Instante]]])
