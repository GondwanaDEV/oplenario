(ns oplenario.participacao.events.solicitacao-titular
  "Eventos de dominio da SOLICITACAO do titular LGPD (§22.10 events/, ADR-0001). `.protocolada` marca o inicio do
  relogio LGPD (CONTADOR SEPARADO do e-SIC); `.respondida` o desfecho. Datas/instantes viajam como STRING ISO
  (jsonista nao serializa java.time). O `ente-id` mora no envelope. Sem PII de CONTEUDO: `detalhe`/corpo ficam no
  banco; o `tipo` (categoria do direito exercido) viaja p/ o rastreamento — nao e' conteudo do titular."
  (:require [oplenario.kernel.eventos :as eventos]))

(def protocolada-tipo "participacao.solicitacao_titular.protocolada")

(def ProtocoladaPayload
  "Payload do protocolo de uma solicitacao do titular. So as chaves de rastreamento do relogio + o tipo do direito."
  [:map {:closed true}
   [:solicitacao-id :uuid]
   [:protocolo :string]
   [:tipo :string]
   [:recibo-em :string]   ; ISO-8601 (instante do recibo)
   [:vence-em :string]])  ; ISO date (vencimento LGPD [GAP])

(defn protocolada [ente-id payload]
  (eventos/evento-validado ProtocoladaPayload protocolada-tipo ente-id payload))

(def respondida-tipo "participacao.solicitacao_titular.respondida")

(def RespondidaPayload
  "Payload da resposta a uma solicitacao do titular. So o rastreamento — a solicitacao + quando foi respondida
  (fecha o relogio LGPD). Sem PII (o corpo da resposta fica no banco)."
  [:map {:closed true}
   [:solicitacao-id :uuid]
   [:respondida-em :string]])  ; ISO-8601 (instante da resposta)

(defn respondida [ente-id payload]
  (eventos/evento-validado RespondidaPayload respondida-tipo ente-id payload))
