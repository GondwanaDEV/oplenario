(ns oplenario.sessoes.diplomat.producers
  "Outbound (ADR-0001 diplomat/): emite os eventos de dominio de TEMPO REAL de sessoes no bus (kernel/eventos ->
  shared.outbox). SEMPRE dentro da `tx` do ato (atomicidade outbox-com-o-ato, §22.9 E2): a linha do evento so
  existe se a tx commitou. Chamado pelo Repo-Component, que compoe o ato + a emissao na MESMA tx (§3-bis). O
  vocabulario/contrato mora em events/; aqui e' so o ATO de emitir. Estes eventos sao a fonte do projetor SSE."
  (:require [oplenario.kernel.eventos :as eventos]
            [oplenario.sessoes.events.ata :as ev-ata]
            [oplenario.sessoes.events.gravacao :as ev-gravacao]
            [oplenario.sessoes.events.incidente :as ev-incidente]
            [oplenario.sessoes.events.pauta :as ev-pauta]
            [oplenario.sessoes.events.presenca :as ev-presenca]
            [oplenario.sessoes.events.sessao :as ev-sessao]
            [oplenario.sessoes.events.tribuna :as ev-tribuna]))

(defn emitir-sessao-agendada! [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-sessao/agendada ente-id payload)))

(defn emitir-sessao-transicionou! [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-sessao/transicionou ente-id payload)))

(defn emitir-presenca-registrada! [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-presenca/registrada ente-id payload)))

(defn emitir-fala-iniciada! [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-tribuna/fala-iniciada ente-id payload)))

(defn emitir-tempos-regimentais-definidos! [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-tribuna/tempos-regimentais-definidos ente-id payload)))

(defn emitir-fala-encerrada! [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-tribuna/fala-encerrada ente-id payload)))

(defn emitir-fala-cronometro! [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-tribuna/fala-cronometro ente-id payload)))

(defn emitir-inscricao-registrada! [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-tribuna/inscricao-registrada ente-id payload)))

(defn emitir-inscricao-desistida! [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-tribuna/inscricao-desistida ente-id payload)))

(defn emitir-ata-rascunho-solicitado! [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-ata/rascunho-solicitado ente-id payload)))

(defn emitir-gravacao-segmento-captado! [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-gravacao/segmento-captado ente-id payload)))

(defn emitir-gravacao-segmento-vinculado! [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-gravacao/segmento-vinculado ente-id payload)))

(defn emitir-incidente-registrado! [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-incidente/registrado ente-id payload)))

(defn emitir-item-anunciado! [bus tx ente-id payload]
  (eventos/emitir! bus tx (ev-pauta/item-anunciado ente-id payload)))
