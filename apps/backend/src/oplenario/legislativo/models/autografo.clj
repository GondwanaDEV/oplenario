(ns oplenario.legislativo.models.autografo
  "Representacao INTERNA (dominio) do AUTOGRAFO — Malli (§22.10 models/, F3.8a). Artefato legal
  append-only: gerado uma vez, imutavel. `texto-versao-id`/`destinatario-id` sao forward-refs (sem FK).
  Carimbos de tempo chegam como java.time.Instant (kernel/db-tipos; timestamptz) -> kernel.malli/Instante."
  (:require [oplenario.kernel.malli :as km]))

(def Autografo
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:proposicao-id :uuid]
   [:numero :int]
   [:ano :int]
   [:texto-versao-id {:optional true} [:maybe :uuid]]
   [:destinatario-texto :string]
   [:destinatario-id {:optional true} [:maybe :uuid]]
   [:enviado-em km/Instante]
   [:prazo-resposta-em {:optional true} [:maybe km/Instante]]])
