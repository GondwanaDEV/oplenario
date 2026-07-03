(ns oplenario.participacao.diplomat.producers
  "Outbound (ADR-0001 diplomat/): emite os eventos de dominio do participacao no bus (kernel/eventos ->
  shared.outbox). SEMPRE dentro da `tx` do ato (atomicidade outbox-com-o-ato, §22.9 E2: a linha do evento so
  existe se a tx commitou). Chamado pelo Repo-Component, que compoe o ato + a emissao na MESMA tx. O
  vocabulario/contrato mora em events/; aqui e' so o ATO de emitir."
  (:require [oplenario.kernel.eventos :as eventos]
            [oplenario.participacao.events.manifestacao-ouvidoria :as ev-manifestacao]
            [oplenario.participacao.events.pedido-esic :as ev-pedido]
            [oplenario.participacao.events.prazo :as ev-prazo]
            [oplenario.participacao.events.recurso-esic :as ev-recurso]
            [oplenario.participacao.events.solicitacao-titular :as ev-titular]))

(defn emitir-pedido-protocolado! [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-pedido/protocolado ente-id payload)))

(defn emitir-pedido-respondido! [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-pedido/respondido ente-id payload)))

(defn emitir-recurso-protocolado! [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-recurso/protocolado ente-id payload)))

(defn emitir-recurso-decidido! [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-recurso/decidido ente-id payload)))

(defn emitir-prazo-vencido! [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-prazo/vencido ente-id payload)))

(defn emitir-solicitacao-titular-protocolada! [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-titular/protocolada ente-id payload)))

(defn emitir-solicitacao-titular-respondida! [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-titular/respondida ente-id payload)))

;; ---- FAST-FOLLOW Slice 5: Ouvidoria (Lei 13.460 art. 10) ----

(defn emitir-manifestacao-protocolada! [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-manifestacao/protocolada ente-id payload)))

(defn emitir-manifestacao-respondida! [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-manifestacao/respondida ente-id payload)))

(defn emitir-manifestacao-arquivada! [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-manifestacao/arquivada ente-id payload)))

(defn emitir-prazo-prorrogado! [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-prazo/prorrogado ente-id payload)))
