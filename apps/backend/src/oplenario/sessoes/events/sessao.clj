(ns oplenario.sessoes.events.sessao
  "Eventos de dominio da SESSAO (ADR-0001: events/ = nome + schema Malli do payload). O ENVELOPE (tipo +
  ente-id + idempotency-key) vem do kernel; aqui mora o VOCABULARIO — nome + CONTRATO do payload, validado na
  construcao (um evento mal-formado nunca chega ao shared.outbox, §22.9 E2). Fonte do projetor SSE (§22.6 eixo
  G): o canal `sessao/{id}/plenario` reage a abertura/suspensao/encerramento."
  (:require [oplenario.kernel.eventos :as eventos]))

(def transicionou-tipo "sessao.transicionou")

(def TransicionouPayload
  "Transicao OCORRIDA na maquina da sessao (espelha {:de :para} de db/sessao/transicionar!)."
  [:map {:closed true}
   [:sessao-id :uuid]
   [:de :string]
   [:para :string]
   [:ator-id {:optional true} [:maybe :uuid]]])

(defn transicionou [ente-id payload]
  (eventos/evento-validado TransicionouPayload transicionou-tipo ente-id payload))
