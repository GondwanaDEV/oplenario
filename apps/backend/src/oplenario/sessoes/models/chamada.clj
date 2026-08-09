(ns oplenario.sessoes.models.chamada
  "Representacao INTERNA (dominio) do ATO DE CHAMADA CONDUZIDA (§22.6 eixo C, Etapa 2d) — Malli (§22.10
  models/). Append-only puro (mig 0072). NAO ha' um enum aqui: `membros-da-casa` e' um invariante NUMERICO
  (`>= 0`), nao um vocabulario de texto — por isso nao ha' um `logic/tipos-*` a espelhar (contraste com
  `models/incidente`)."
  (:require [oplenario.kernel.malli :as km]))

(def ChamadaConduzida
  "O ato: quando foi conduzida (`ocorrido-em`, DOMINIO) e quando o sistema soube (`registrado-em`, AUDIT —
  mesmo par de `models.presenca`), quem conduziu, e quantos membros a Casa tinha NAQUELE instante
  (`membros-da-casa`, o denominador do quorum CONGELADO)."
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:sessao-id :uuid]
   [:conduzida-por :uuid]
   [:membros-da-casa [:int {:min 0}]]
   [:ocorrido-em km/Instante]
   [:registrado-em km/Instante]])
