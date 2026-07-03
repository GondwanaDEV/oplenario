(ns oplenario.participacao.events.comentario
  "Eventos de dominio do COMENTARIO (§22.10 events/, ADR-0001). `.protocolado` marca a criacao (sempre
  'pendente'); `.moderado` o desfecho da moderacao; `.denunciado` o ato de denunciar (so' emitido na 1a
  denuncia de um cidadao sobre um comentario — repeticoes idempotentes NAO reemitem, ver
  components/repositorio). Sem PII: `autor-identidade-id`/`denunciante-identidade-id`/`corpo` NUNCA entram no
  payload (mesmo racional de pedido_esic/manifestacao_ouvidoria — o conteudo fica no banco). O `ente-id` mora
  no envelope."
  (:require [oplenario.kernel.eventos :as eventos]))

(def protocolado-tipo "participacao.comentario.protocolado")

(def ProtocoladoPayload
  "Payload da criacao de um comentario. Sem PII (corpo/autor ficam no banco); so as chaves de rastreamento."
  [:map {:closed true}
   [:comentario-id :uuid]
   [:proposicao-id :uuid]])

(defn protocolado [ente-id payload]
  (eventos/evento-validado ProtocoladoPayload protocolado-tipo ente-id payload))

(def moderado-tipo "participacao.comentario.moderado")

(def ModeradoPayload
  "Payload da decisao de moderacao. Sem PII (quem moderou fica na trilha, nao no evento); so o desfecho."
  [:map {:closed true}
   [:comentario-id :uuid]
   [:acao :string]])   ; "aprovado" | "rejeitado"

(defn moderado [ente-id payload]
  (eventos/evento-validado ModeradoPayload moderado-tipo ente-id payload))

(def denunciado-tipo "participacao.comentario.denunciado")

(def DenunciadoPayload
  "Payload do ato de denunciar (so' emitido na 1a denuncia — repeticoes idempotentes nao reemitem). Sem PII
  (o denunciante fica no registro de denuncia_comentario, nao no evento)."
  [:map {:closed true}
   [:comentario-id :uuid]])

(defn denunciado [ente-id payload]
  (eventos/evento-validado DenunciadoPayload denunciado-tipo ente-id payload))
