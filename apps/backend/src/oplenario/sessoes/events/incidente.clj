(ns oplenario.sessoes.events.incidente
  "Evento de dominio do INCIDENTE PROCESSUAL (§16.13, ADR-0001 events/). Fonte do projetor SSE da MESA DE
  CONDUCAO ao vivo (§22.6 eixo G): o painel de quem preside reage ao incidente suscitado (pedido de vista,
  verificacao, urgencia, votacao em bloco) em tempo real. `ocorrido-em` viaja como ISO-8601 string (jsonista
  nao serializa java.time.Instant). Sem campos sensiveis (o incidente e' ato publico de conducao)."
  (:require [oplenario.kernel.eventos :as eventos]))

(def registrado-tipo "incidente.registrado")

(def RegistradoPayload
  "Evento de incidente processual registrado (espelha a linha de incidente_processual, MENOS `deliberacao`:
  texto longo OMITIDO de proposito do evento SSE — o painel ao vivo nao precisa dela; a ata le do banco).
  `objeto-*`/`requerente-id` opcionais (votacao em bloco nao tem objeto; incidente da Mesa nao tem requerente)."
  [:map {:closed true}
   [:incidente-id :uuid]
   [:sessao-id :uuid]
   [:tipo :string]
   [:resultado :string]
   [:ocorrido-em :string]
   [:objeto-tipo {:optional true} :string]
   [:objeto-id {:optional true} :uuid]
   [:requerente-id {:optional true} :uuid]])

(defn registrado [ente-id payload]
  (eventos/evento-validado RegistradoPayload registrado-tipo ente-id payload))
