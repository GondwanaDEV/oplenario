(ns oplenario.participacao.events.manifestacao-ouvidoria
  "Eventos de dominio da MANIFESTACAO de ouvidoria (§22.10 events/, ADR-0001). `.protocolada` marca o
  inicio do relogio Lei 13.460 art. 10; `.respondida`/`.arquivada` o desfecho (com/sem merito). `recibo-em`/
  `respondida-em`/`arquivada-em` viajam como STRING ISO (jsonista nao serializa java.time). O `ente-id` mora
  no envelope. Sem PII: `manifestante-identidade-id` NUNCA entra no payload (mesmo quando NAO-anonima —
  simetria com pedido_esic, que tambem nao carrega o solicitante no evento)."
  (:require [oplenario.kernel.eventos :as eventos]))

(def protocolada-tipo "participacao.manifestacao_ouvidoria.protocolada")

(def ProtocoladaPayload
  "Payload do protocolo de uma manifestacao de ouvidoria. Sem PII (assunto/descricao/manifestante ficam no
  banco); so as chaves de rastreamento do relogio."
  [:map {:closed true}
   [:manifestacao-id :uuid]
   [:protocolo :string]
   [:recibo-em :string]    ; ISO-8601 (instante do recibo)
   [:vence-em :string]])   ; ISO date (vencimento Lei 13.460)

(defn protocolada [ente-id payload]
  (eventos/evento-validado ProtocoladaPayload protocolada-tipo ente-id payload))

(def respondida-tipo "participacao.manifestacao_ouvidoria.respondida")

(def RespondidaPayload
  "Payload da resposta (com merito) a uma manifestacao. Sem PII (o corpo da resposta fica no banco); so as
  chaves de rastreamento — manifestacao, protocolo, e quando foi respondida (fecha o relogio)."
  [:map {:closed true}
   [:manifestacao-id :uuid]
   [:protocolo :string]
   [:respondida-em :string]])  ; ISO-8601 (instante da resposta)

(defn respondida [ente-id payload]
  (eventos/evento-validado RespondidaPayload respondida-tipo ente-id payload))

(def arquivada-tipo "participacao.manifestacao_ouvidoria.arquivada")

(def ArquivadaPayload
  "Payload do arquivamento (SEM merito) de uma manifestacao. Sem PII (o motivo fica no banco); so as chaves
  de rastreamento — manifestacao, protocolo, e quando foi arquivada (cancela o relogio, nao cumpre)."
  [:map {:closed true}
   [:manifestacao-id :uuid]
   [:protocolo :string]
   [:arquivada-em :string]])  ; ISO-8601 (instante do arquivamento)

(defn arquivada [ente-id payload]
  (eventos/evento-validado ArquivadaPayload arquivada-tipo ente-id payload))
