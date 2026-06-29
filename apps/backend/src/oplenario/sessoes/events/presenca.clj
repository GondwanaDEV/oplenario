(ns oplenario.sessoes.events.presenca
  "Evento de dominio da PRESENCA (ADR-0001: events/). Fonte do projetor SSE (§22.6 eixo G): o painel ao vivo
  reage a entrada/saida p/ recompor o quorum. `ocorrido-em` viaja como ISO-8601 string (jsonista nao serializa
  java.time.Instant; o cliente recebe string de qualquer forma)."
  (:require [oplenario.kernel.eventos :as eventos]))

(def registrada-tipo "presenca.registrada")

(def RegistradaPayload
  "Evento de presenca registrado (espelha a linha de presenca_evento)."
  [:map {:closed true}
   [:sessao-id :uuid]
   [:vereador-id :uuid]
   [:tipo :string]
   [:modalidade :string]
   [:fonte :string]
   [:ocorrido-em :string]])

(defn registrada [ente-id payload]
  (eventos/evento-validado RegistradaPayload registrada-tipo ente-id payload))
