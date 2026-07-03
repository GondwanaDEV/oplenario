(ns oplenario.transparencia.models.acompanhamento
  "Representacao INTERNA (dominio) do ACOMPANHAMENTO — a subscricao do cidadao a uma materia (§22.10 models/,
  ADR-0001, F6c Slice 2). VERDADE de dominio (nao projecao): o registro do consentimento de ser notificado
  (§22.5). Carimbos via kernel.malli/Instante (timestamptz)."
  (:require [oplenario.kernel.malli :as km]))

(def Acompanhamento
  [:map {:closed true}
   [:ente-id km/EnteId]
   [:id :uuid]
   [:proposicao-id :uuid]
   [:seguidor-identidade-id :uuid]
   [:estado [:enum "ativo" "cancelado"]]
   [:created-by {:optional true} [:maybe :uuid]]
   [:criado-em km/Instante]
   [:atualizado-em km/Instante]])
