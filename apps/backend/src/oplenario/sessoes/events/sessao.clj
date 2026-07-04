(ns oplenario.sessoes.events.sessao
  "Eventos de dominio da SESSAO (ADR-0001: events/ = nome + schema Malli do payload). O ENVELOPE (tipo +
  ente-id + idempotency-key) vem do kernel; aqui mora o VOCABULARIO — nome + CONTRATO do payload, validado na
  construcao (um evento mal-formado nunca chega ao shared.outbox, §22.9 E2). Fonte do projetor SSE (§22.6 eixo
  G): o canal `sessao/{id}/plenario` reage a abertura/suspensao/encerramento."
  (:require [oplenario.kernel.eventos :as eventos]))

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
