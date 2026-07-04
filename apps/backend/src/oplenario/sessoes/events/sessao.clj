(ns oplenario.sessoes.events.sessao
  "Eventos de dominio da SESSAO (ADR-0001: events/ = nome + schema Malli do payload). O ENVELOPE (tipo +
  ente-id + idempotency-key) vem do kernel; aqui mora o VOCABULARIO — nome + CONTRATO do payload, validado na
  construcao (um evento mal-formado nunca chega ao shared.outbox, §22.9 E2). Fonte do projetor SSE (§22.6 eixo
  G): o canal `sessao/{id}/plenario` reage a abertura/suspensao/encerramento."
  (:require [oplenario.kernel.eventos :as eventos]))

(def agendada-tipo "sessao.agendada")

(def AgendadaPayload
  "Nascimento da sessao (F7 E3 carry): `agendar!` cria a sessao em 'agendada' e AGORA emite este evento p/ o SLI
  de janela de sessao (paineis) materializar a linha JA' no agendamento — sem ele, o SLI so' via a sessao a
  partir da 1a transicao, cego ao no-show 100% silencioso (agendada que nunca sequer foi tocada), que e' o
  headline failure do proprio Inv.9. `agendada-para` = quando a sessao esta marcada (p/ o painel dizer 'a de
  quarta nao aconteceu'; opcional — nem toda sessao tem data cravada). `ocorrido-em` = instante do ATO de
  agendar (`efetivado_em`, RETURNING) — semeia `transicionou_em` do SLI, sempre <= qualquer transicao futura
  (o gate de monotonicidade absorve a ordem). Instantes viajam como STRING ISO (jsonista nao serializa Instant)."
  [:map {:closed true}
   [:sessao-id :uuid]
   [:agendada-para {:optional true} [:maybe :string]]
   [:ocorrido-em :string]])

(defn agendada [ente-id payload]
  (eventos/evento-validado AgendadaPayload agendada-tipo ente-id payload))

(def transicionou-tipo "sessao.transicionou")

(def TransicionouPayload
  "Transicao OCORRIDA na maquina da sessao (espelha {:de :para} de db/sessao/transicionar!). `ocorrido-em`
  (F7 E3, SLI de janela de sessao — mirror do carry fechado em legislativo/proposicao a5a5532): o instante
  REAL da transicao no dominio (`sessoes.sessao.atualizado_em`, RETURNING do UPDATE de transicionar!) — nao o
  momento em que um consumer eventualmente PROJETA o evento. O projetor de SLI (paineis.sli_sessao) carimba
  aberta_em/encerrada_em/janela DESTE instante; sem ele, so' teria 'agora' (tempo de PROCESSAMENTO), que
  mente sob qualquer atraso comum do relay (deploy, backpressure) exatamente quando o sinal mais importa.
  Viaja como STRING ISO (jsonista nao serializa java.time.Instant, mesma disciplina dos demais eventos)."
  [:map {:closed true}
   [:sessao-id :uuid]
   [:de :string]
   [:para :string]
   [:ocorrido-em :string]
   [:ator-id {:optional true} [:maybe :uuid]]])

(defn transicionou [ente-id payload]
  (eventos/evento-validado TransicionouPayload transicionou-tipo ente-id payload))
