(ns oplenario.normas.events.norma
  "Evento de dominio das normas de referencia (ADR-0011) — ADR-0001: events/ = nome + schema Malli do payload.
  `norma.versao-vigente` sai DENTRO da tx da conferencia que publica a versao (atomicidade outbox-com-o-ato §22.9 E2).
  Leva so' identidade: quem precisa do texto (o indice da IA, B.4b) le os dispositivos vigentes pela fronteira."
  (:require [oplenario.kernel.eventos :as eventos]))

(def vigente-tipo "norma.versao-vigente")

(def VigentePayload
  [:map {:closed true}
   [:norma-id :uuid]
   [:versao-id :uuid]
   [:especie :string]])

(defn vigente [ente-id payload]
  (eventos/evento-validado VigentePayload vigente-tipo ente-id payload))
