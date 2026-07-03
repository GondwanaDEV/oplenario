(ns oplenario.participacao.models.prorrogacao
  "Representacao INTERNA (dominio) da PRORROGACAO (§22.10 models/, ADR-0001) — Malli. APPEND-ONLY (Inv.10):
  o REGISTRO de auditoria de uma prorrogacao de prazo. Polimorfica (objeto-tipo/objeto-id, mesma forma de
  prazo-ativo/models) — serve qualquer especie de prazo, nao so ouvidoria (disciplina 5)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.participacao.logic :as logic]
            [oplenario.participacao.models.prazo-ativo :as prazo-ativo]))

(def Prorrogacao
  "Prorrogacao persistida (participacao.prorrogacao)."
  [:map {:closed true}
   [:id :uuid]
   [:ente-id km/EnteId]
   [:objeto-tipo (km/enum-de logic/objeto-tipos-prazo)]
   [:objeto-id :uuid]
   [:de-data prazo-ativo/Data]
   [:para-data prazo-ativo/Data]
   [:justificativa [:string {:min 1}]]
   [:prorrogado-por :uuid]
   [:prorrogado-em km/Instante]
   [:criado-em km/Instante]])
