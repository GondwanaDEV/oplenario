(ns oplenario.transparencia.models.norma
  "Representacao INTERNA (dominio) da NORMA no read-model do portal (§22.10 models/, ADR-0001, F6c Slice 1) —
  Malli. Projecao PUBLICA de uma norma PUBLICADA de `legislativo` (evento `norma.publicada`, marco de
  eficacia). Imutavel apos projetada (INSERT-only — a norma-vista nunca muta; a fonte de verdade e' o
  `legislativo`). Carimbos via kernel.malli/Instante (timestamptz)."
  (:require [oplenario.kernel.malli :as km]))

(def Norma
  [:map {:closed true}
   [:ente-id :uuid]
   [:norma-id :uuid]
   [:proposicao-id :uuid]
   [:tipo-norma :string]
   [:numero :int]
   [:ano :int]
   [:urn :string]
   [:ementa :string]
   [:publicado-em km/Instante]
   [:veiculo-publicacao :string]
   [:projetado-em km/Instante]])
