(ns oplenario.participacao.models.resposta-ouvidoria
  "Representacao INTERNA (dominio) da RESPOSTA/JUSTIFICATIVA de ouvidoria (§22.10 models/, ADR-0001) —
  Malli. APPEND-ONLY (Inv.10): o ato de responder OU arquivar uma manifestacao. `respondido-por` = o
  servidor autor (injetado do ator)."
  (:require [oplenario.kernel.malli :as km]))

(def RespostaOuvidoria
  "Resposta/justificativa de ouvidoria persistida (participacao.resposta_ouvidoria)."
  [:map {:closed true}
   [:id :uuid]
   [:ente-id km/EnteId]
   [:manifestacao-id :uuid]
   [:corpo [:string {:min 1}]]
   [:respondido-por :uuid]
   [:respondida-em km/Instante]
   [:criado-em km/Instante]])
