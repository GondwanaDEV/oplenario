(ns oplenario.participacao.models.resposta-titular
  "Representacao INTERNA (dominio) da RESPOSTA a uma solicitacao do titular LGPD (§22.10 models/, ADR-0001) —
  Malli. APPEND-ONLY (Inv.10): o ato de responder uma solicitacao. Cita UMA `solicitacao-id` (FK composta no
  banco, same-tenant). `respondido-por` = o servidor/Encarregado autor (injetado do ator)."
  (:require [oplenario.kernel.malli :as km]))

(def RespostaTitular
  "Resposta a uma solicitacao do titular persistida (participacao.resposta_titular)."
  [:map {:closed true}
   [:id :uuid]
   [:ente-id km/EnteId]
   [:solicitacao-id :uuid]
   [:corpo [:string {:min 1}]]
   [:respondido-por :uuid]
   [:respondida-em km/Instante]
   [:criado-em km/Instante]])
