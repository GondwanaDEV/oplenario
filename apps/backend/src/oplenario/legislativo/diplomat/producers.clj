(ns oplenario.legislativo.diplomat.producers
  "Outbound (ADR-0001 diplomat/): emite os eventos de dominio do legislativo no bus (via kernel/eventos ->
  shared.outbox). SEMPRE dentro da `tx` do ato (atomicidade outbox-com-o-ato, §22.9 E2): a linha do evento
  so existe se a tx commitou. Chamado pelo Repo-Component, que compoe o ato + a emissao na MESMA tx do
  tenant (§3-bis). A vocabulario/contrato do evento mora em events/; aqui e' so o ATO de emitir."
  (:require [oplenario.kernel.eventos :as eventos]
            [oplenario.legislativo.events.proposicao :as ev]))

(defn emitir-transicionou!
  "Emite `proposicao.transicionou` no `bus` DENTRO da `tx` corrente. `payload` casa events/TransicionouPayload."
  [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev/transicionou ente-id payload)))
