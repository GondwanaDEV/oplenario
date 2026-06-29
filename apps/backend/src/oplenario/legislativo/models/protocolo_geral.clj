(ns oplenario.legislativo.models.protocolo-geral
  "Representacao INTERNA (dominio) do PROTOCOLO GERAL — Malli (§22.10 models/, F3.9a). Registro append-only
  do livro institucional; objeto POLIMORFICO (objeto_tipo/objeto_id). Os enums vem de legislativo.logic
  (fonte unica; os CHECK da mig 0024 espelham). Carimbos via kernel.malli/Instante (timestamptz)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.legislativo.logic :as logic]))

(defn- enum-de [s] (into [:enum] (sort s)))

(def ProtocoloGeral
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:numero :int]
   [:ano :int]
   [:objeto-tipo (enum-de logic/objetos-protocolo)]
   [:objeto-id {:optional true} [:maybe :uuid]]
   [:sentido (enum-de logic/sentidos-protocolo)]
   [:assunto :string]
   [:interessado-texto {:optional true} [:maybe :string]]
   [:interessado-id {:optional true} [:maybe :uuid]]
   [:protocolado-em km/Instante]
   [:protocolado-por {:optional true} [:maybe :uuid]]])
