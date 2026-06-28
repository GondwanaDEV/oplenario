(ns oplenario.legislativo.events.proposicao
  "Eventos de dominio do legislativo (ADR-0001: events/ = nome + schema Malli do payload). O ENVELOPE
  (tipo + ente-id + idempotency-key) vem do kernel (eventos/evento); aqui mora o VOCABULARIO — o nome do
  evento e o CONTRATO do payload, validado na construcao: um evento mal-formado nunca chega ao
  shared.outbox (que e' cross-modulo e duravel — §22.9 E2). O `wire/out` (Eixo 8) projeta este contrato
  p/ os consumidores; aqui e' a representacao interna do payload."
  (:require [malli.core :as m]
            [oplenario.kernel.eventos :as eventos]))

(def transicionou-tipo
  "Nome do evento emitido quando a maquina de tramitacao (eixo C) move a proposicao de estado."
  "proposicao.transicionou")

(def TransicionouPayload
  "Payload de `proposicao.transicionou` — a transicao OCORRIDA (espelha a linha de
  proposicao_transicao_historico, a prova duravel da mudanca, Inv.10)."
  [:map {:closed true}
   [:proposicao-id :uuid]
   [:template-id :uuid]
   [:de :string]
   [:para :string]
   [:gatilho :string]
   [:transicao-id :uuid]
   [:ator-id {:optional true} [:maybe :uuid]]])

(defn transicionou
  "Constroi o envelope de `proposicao.transicionou` p/ o tenant `ente-id`, VALIDANDO o payload contra o
  contrato. Lanca :payload-invalido se nao casa — defesa na fonte: o outbox so recebe evento bem-formado."
  [ente-id payload]
  (when-not (m/validate TransicionouPayload payload)
    (throw (ex-info "payload de proposicao.transicionou invalido (contrato do evento)"
                    {:erro :payload-invalido :explain (m/explain TransicionouPayload payload)})))
  (eventos/evento transicionou-tipo ente-id payload))
