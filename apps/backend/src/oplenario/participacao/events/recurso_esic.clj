(ns oplenario.participacao.events.recurso-esic
  "Eventos de dominio do RECURSO e-SIC (§22.10 events/, ADR-0001). `.protocolado` marca o inicio do relogio
  PROPRIO do recurso (independente do pedido); `.decidido` o desfecho. `recibo-em`/`vence-em`/`decidido-em`
  viajam como STRING ISO (jsonista nao serializa java.time). O `ente-id` mora no envelope. Sem PII (motivo/corpo
  ficam no banco)."
  (:require [oplenario.kernel.eventos :as eventos]))

(def protocolado-tipo "participacao.recurso_esic.protocolado")

(def ProtocoladoPayload
  "Payload da interposicao de um recurso. Carrega o pedido recorrido + as chaves do relogio proprio do recurso."
  [:map {:closed true}
   [:recurso-id :uuid]
   [:pedido-id :uuid]
   [:protocolo :string]
   [:recibo-em :string]   ; ISO-8601 (instante do recibo do recurso)
   [:vence-em :string]])  ; ISO date (vencimento do prazo proprio do recurso)

(defn protocolado [ente-id payload]
  (eventos/evento-validado ProtocoladoPayload protocolado-tipo ente-id payload))

(def decidido-tipo "participacao.recurso_esic.decidido")

(def DecididoPayload
  "Payload da decisao de um recurso. So o recurso + quando foi decidido (fecha o relogio proprio do recurso)."
  [:map {:closed true}
   [:recurso-id :uuid]
   [:decidido-em :string]])  ; ISO-8601 (instante da decisao)

(defn decidido [ente-id payload]
  (eventos/evento-validado DecididoPayload decidido-tipo ente-id payload))
