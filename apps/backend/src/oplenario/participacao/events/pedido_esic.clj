(ns oplenario.participacao.events.pedido-esic
  "Evento de dominio do PEDIDO e-SIC (§22.10 events/, ADR-0001). `participacao.pedido_esic.protocolado` marca
  o inicio do relogio LAI — fonte futura da metrica institucional §16.11 ('a Casa responde e-SIC no prazo?',
  DIFERIDA: vira read-model do proprio participacao, NUNCA dependencia do compliance). `recibo-em`/`vence-em`
  viajam como STRING ISO (jsonista nao serializa java.time.Instant/LocalDate). O `ente-id` mora no envelope."
  (:require [oplenario.kernel.eventos :as eventos]))

(def protocolado-tipo "participacao.pedido_esic.protocolado")

(def ProtocoladoPayload
  "Payload do protocolo de um pedido e-SIC. Sem PII (assunto/descricao/solicitante ficam no banco); so as
  chaves de rastreamento do relogio."
  [:map {:closed true}
   [:pedido-id :uuid]
   [:protocolo :string]
   [:recibo-em :string]   ; ISO-8601 (instante do recibo)
   [:vence-em :string]])  ; ISO date (vencimento LAI)

(defn protocolado [ente-id payload]
  (eventos/evento-validado ProtocoladoPayload protocolado-tipo ente-id payload))
