(ns oplenario.sessoes.events.ata
  "Evento de fronteira CORE->IA da ata (Faixa A / A.6b, §22.3.3): `ata.rascunho-solicitado` — a secretaria pediu o
  rascunho da ata de uma sessao. Consumidor = `integracao_ia` (vira `AtaSolicitada` no feed da IA), NAO o projetor
  SSE. So' ids: a IA le o contexto e as transcricoes que ela mesma guarda."
  (:require [oplenario.kernel.eventos :as eventos]))

(def rascunho-solicitado-tipo "ata.rascunho-solicitado")

(def RascunhoSolicitadoPayload
  [:map {:closed true}
   [:solicitacao-id :uuid]
   [:sessao-id :uuid]])

(defn rascunho-solicitado [ente-id payload]
  (eventos/evento-validado RascunhoSolicitadoPayload rascunho-solicitado-tipo ente-id payload))
