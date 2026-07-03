(ns oplenario.transparencia.models.materia
  "Representacao INTERNA (dominio) da MATERIA no read-model do portal (§22.10 models/, ADR-0001, F6c Slice 1)
  — Malli. Projecao PUBLICA de uma proposicao de `legislativo`: snapshot no protocolo (`proposicao.protocolada`)
  + `estado` atualizado a cada transicao (`proposicao.transicionou`). SEM PII: o autor e' representado por
  VALOR (`autor-tipo`/`autor-texto` de exibicao), nunca por `autor-id` interno (§22.10 — sem FK/JOIN cross-schema;
  o dado chega via evento, ja' e' a vista publica). Carimbos via kernel.malli/Instante (timestamptz)."
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
   [:estado :string]
   [:projetado-em km/Instante]
   [:atualizado-em km/Instante]])
