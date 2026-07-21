(ns oplenario.legislativo.diplomat.producers
  "Outbound (ADR-0001 diplomat/): emite os eventos de dominio do legislativo no bus (via kernel/eventos ->
  shared.outbox). SEMPRE dentro da `tx` do ato (atomicidade outbox-com-o-ato, §22.9 E2): a linha do evento
  so existe se a tx commitou. Chamado pelo Repo-Component, que compoe o ato + a emissao na MESMA tx do
  tenant (§3-bis). A vocabulario/contrato do evento mora em events/; aqui e' so o ATO de emitir."
  (:require [oplenario.kernel.eventos :as eventos]
            [oplenario.legislativo.events.artefato-publicacao :as ev-artefato]
            [oplenario.legislativo.events.norma :as ev-norma]
            [oplenario.legislativo.events.notificacao :as ev-notificacao]
            [oplenario.legislativo.events.parecer :as ev-parecer]
            [oplenario.legislativo.events.proposicao :as ev]
            [oplenario.legislativo.events.votacao :as ev-votacao]))

(defn emitir-protocolada!
  "Emite `proposicao.protocolada` no `bus` DENTRO da `tx` corrente (gate eixo H). `payload` casa
  events/ProtocoladaPayload — o snapshot publico que o portal (transparencia) projeta."
  [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev/protocolada ente-id payload)))

(defn emitir-transicionou!
  "Emite `proposicao.transicionou` no `bus` DENTRO da `tx` corrente. `payload` casa events/TransicionouPayload."
  [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev/transicionou ente-id payload)))

(defn emitir-editada!
  "Emite `proposicao.editada` no `bus` DENTRO da `tx` corrente (Task 1-N1). `payload` casa
  events/EditadaPayload — o snapshot publico pos-PATCH que o portal (transparencia) projeta."
  [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev/editada ente-id payload)))

(defn emitir-norma-publicada!
  "Emite `norma.publicada` no `bus` DENTRO da `tx` corrente (F3.8b, marco de eficacia). `payload` casa
  events.norma/PublicadaPayload — o snapshot publico que o portal (transparencia) projeta."
  [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-norma/publicada ente-id payload)))

(defn emitir-transicionou-parecer!
  "Emite `parecer.transicionou` no `bus` DENTRO da `tx` corrente (eixo F). `payload` casa
  events.parecer/TransicionouPayload."
  [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-parecer/transicionou ente-id payload)))

(defn emitir-artefato-publicacao-gerado!
  "Emite `artefato.publicacao.gerado` no `bus` DENTRO da `tx` do INSERT (F6c Slice 4b, §22.9 E2). `payload`
  casa events.artefato-publicacao/GeradoPayload — o snapshot publico que o portal (transparencia) projeta p/
  exibir e servir o download."
  [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-artefato/gerado ente-id payload)))

;; --- eixo G / carry F4 — votacao (fonte do placar ao vivo, §22.6 eixo G). ---

(defn emitir-votacao-aberta!
  "Emite `votacao.aberta` no `bus` DENTRO da `tx` corrente. `payload` casa events.votacao/AbertaPayload."
  [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-votacao/aberta ente-id payload)))

(defn emitir-voto-registrado!
  "Emite `voto.registrado` no `bus` DENTRO da `tx` corrente. `payload` casa events.votacao/VotoRegistradoPayload
  (uniao discriminada: nominal carrega vereador/voto; secreta e' tick — sigilo §22.6)."
  [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-votacao/voto-registrado ente-id payload)))

(defn emitir-notificacao-requisitada!
  "Emite `notificacao.requisitada` no `bus` DENTRO da `tx` corrente (Onda E fatia 1). Aqui a `tx` e' a do
  RELAY (event-chaining, §22.9 E2: a linha nova commita junto com o dedup do evento-gatilho e o relay a
  drena na iteracao seguinte) — mesma mecanica do fan-out de transparencia. `payload` casa
  events.notificacao/RequisitadaPayload."
  [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-notificacao/requisitada ente-id payload)))

(defn emitir-votacao-encerrada!
  "Emite `votacao.encerrada` no `bus` DENTRO da `tx` corrente. `payload` casa events.votacao/EncerradaPayload."
  [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-votacao/encerrada ente-id payload)))
