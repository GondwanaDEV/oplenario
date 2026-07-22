(ns oplenario.sessoes.events.presenca
  "Evento de dominio da PRESENCA (ADR-0001: events/). Fonte do projetor SSE (§22.6 eixo G): o painel ao vivo
  reage a entrada/saida p/ recompor o quorum. `ocorrido-em` viaja como ISO-8601 string (jsonista nao serializa
  java.time.Instant; o cliente recebe string de qualquer forma)."
  (:require [oplenario.kernel.eventos :as eventos]
            [oplenario.kernel.malli :as km]
            [oplenario.sessoes.logic :as logic]))

(def registrada-tipo "presenca.registrada")

(def RegistradaPayload
  "Evento de presenca registrado (espelha a linha de presenca_evento).

  `tipo`/`modalidade`/`fonte` sao ENUM alimentado por `sessoes.logic` — a MESMA fonte de
  `models/presenca/PresencaEvento` e dos CHECK da mig 0029. Ate' a revisao da fatia 1 do carry I-5 os tres
  eram `:string` CRU aqui, e este e' o unico contrato que ATRAVESSA a fronteira do modulo: o consumidor
  (`transparencia`) copia `:tipo` sem validar enum (e tem de continuar tolerante — o relay e' compartilhado e
  nao pode lancar). Ou seja, o contrato publico era o mais frouxo dos tres, e aceitava
  'presente'/'presencial'/'mesa' — valores que produtor nenhum emite. Foi por essa porta que o vocabulario
  ficticio entrou nos testes de `transparencia` e manteve vivo o numerador morto `tipo = 'presente'`. O
  produtor real (`db/presenca/registrar-evento!`) ja' era fail-closed contra os mesmos conjuntos, entao o
  aperto nao estreita ninguem — so' fecha a porta de quem escreve o payload a mao."
  [:map {:closed true}
   [:sessao-id :uuid]
   [:vereador-id :uuid]
   [:tipo (km/enum-de logic/tipos-evento-presenca)]
   [:modalidade (km/enum-de logic/modalidades-presenca)]
   [:fonte (km/enum-de logic/fontes-presenca)]
   [:ocorrido-em :string]])

(defn registrada [ente-id payload]
  (eventos/evento-validado RegistradaPayload registrada-tipo ente-id payload))
