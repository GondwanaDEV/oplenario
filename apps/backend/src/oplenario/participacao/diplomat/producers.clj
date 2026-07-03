(ns oplenario.participacao.diplomat.producers
  "Outbound (ADR-0001 diplomat/): emite os eventos de dominio do participacao no bus (kernel/eventos ->
  shared.outbox). SEMPRE dentro da `tx` do ato (atomicidade outbox-com-o-ato, §22.9 E2: a linha do evento so
  existe se a tx commitou). Chamado pelo Repo-Component, que compoe o ato + a emissao na MESMA tx. O
  vocabulario/contrato mora em events/; aqui e' so o ATO de emitir."
  (:require [oplenario.kernel.eventos :as eventos]
            [oplenario.participacao.events.pedido-esic :as ev-pedido]))

(defn emitir-pedido-protocolado! [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-pedido/protocolado ente-id payload)))
