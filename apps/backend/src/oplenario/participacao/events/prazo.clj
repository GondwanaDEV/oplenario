(ns oplenario.participacao.events.prazo
  "Eventos de dominio do PRAZO (§22.10 events/, ADR-0001), POLIMORFICOS — servem qualquer objeto_tipo
  (pedido_esic/recurso_esic/solicitacao_titular/manifestacao_ouvidoria), nao um agregado especifico.
  `participacao.prazo.vencido` e' o `acao_no_vencimento` V1 (§22.7.7): quando o sweep detecta um prazo
  pendente estritamente vencido (vencimento EFETIVO, generalizacao 0042), emite este evento na MESMA tx do
  CAS. `participacao.prazo.prorrogado` (fast-follow Slice 5, Lei 13.460 art. 10) marca o ato de prorrogar
  (1x apenas). Escalada/notificacao ao servidor sao CONSUMERS futuros (nao logica aqui). Sem PII: so as
  chaves de rastreamento (objeto_tipo/objeto_id + datas como ISO string — jsonista nao serializa
  java.time.LocalDate). O `ente-id` mora no envelope."
  (:require [oplenario.kernel.eventos :as eventos]))

(def vencido-tipo "participacao.prazo.vencido")

(def VencidoPayload
  "Payload do vencimento de um prazo. Sem PII (assunto/descricao/solicitante ficam no banco); so o objeto
  polimorfico sob prazo (objeto_tipo/objeto_id) + a data de vencimento EFETIVA cruzada."
  [:map {:closed true}
   [:objeto-tipo :string]
   [:objeto-id :uuid]
   [:vence-em :string]])  ; ISO date (o vencimento EFETIVO cruzado — logic/vencimento-efetivo)

(defn vencido [ente-id payload]
  (eventos/evento-validado VencidoPayload vencido-tipo ente-id payload))

(def prorrogado-tipo "participacao.prazo.prorrogado")

(def ProrrogadoPayload
  "Payload da prorrogacao (1x apenas) de um prazo. Sem PII; so o objeto polimorfico + as datas de/para."
  [:map {:closed true}
   [:objeto-tipo :string]
   [:objeto-id :uuid]
   [:de-data :string]     ; ISO date (vence_em ORIGINAL, antes da prorrogacao)
   [:para-data :string]]) ; ISO date (o novo prorrogado_ate)

(defn prorrogado [ente-id payload]
  (eventos/evento-validado ProrrogadoPayload prorrogado-tipo ente-id payload))
