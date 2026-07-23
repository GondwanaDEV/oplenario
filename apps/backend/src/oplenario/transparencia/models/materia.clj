(ns oplenario.transparencia.models.materia
  "Representacao INTERNA (dominio) da MATERIA no read-model do portal (§22.10 models/, ADR-0001, F6c Slice 1)
  — Malli. Projecao PUBLICA de uma proposicao de `legislativo`: snapshot no protocolo (`proposicao.protocolada`)
  + `estado` atualizado a cada transicao (`proposicao.transicionou`). SEM PII: o autor e' representado por
  VALOR (`autor-tipo`/`autor-texto` de exibicao), mais o 'autor-id' (UUID do vereador autor — ator publico), o
  elo do perfil publico (Onda E fatia 2). Carimbos via kernel.malli/Instante (timestamptz)."
  (:require [oplenario.kernel.malli :as km]))

(def Materia
  [:map {:closed true}
   [:ente-id :uuid]
   [:proposicao-id :uuid]
   [:tipo :string]
   [:ano :int]
   [:sequencial :int]
   [:urn-lex :string]
   [:ementa :string]
   [:autor-tipo {:optional true} [:maybe :string]]
   [:autor-texto {:optional true} [:maybe :string]]
   [:autor-id {:optional true} [:maybe :uuid]]
   [:estado :string]
   [:projetado-em km/Instante]
   [:atualizado-em km/Instante]])
