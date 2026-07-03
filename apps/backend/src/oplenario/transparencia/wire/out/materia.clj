(ns oplenario.transparencia.wire.out.materia
  "Representacao EXTERNA de SAIDA da materia (§22.10 wire/out, ADR-0001) — contratos que o `adapters/out`
  produz. Tudo JSON-serializavel (Instant vira string). MateriaOut = item da listagem publica do portal.
  FichaOut = MateriaOut + a norma publicada (se a materia ja' foi promulgada/publicada) — a ligacao
  'proposicao -> lei' que a ficha da materia (16.5) expoe ao cidadao. Sem PII (autor e' so' texto de exibicao)."
  (:require [oplenario.transparencia.wire.out.norma :as wire-norma]))

(def MateriaOut
  [:map {:closed true}
   [:proposicao-id :string]
   [:tipo :string]
   [:ano :int]
   [:sequencial :int]
   [:urn-lex :string]
   [:ementa :string]
   [:autor-tipo {:optional true} [:maybe :string]]
   [:autor-texto {:optional true} [:maybe :string]]
   [:estado :string]])

(def FichaOut
  "MateriaOut + a norma publicada, se houver (:norma ausente/nil = a materia ainda nao virou lei)."
  [:map {:closed true}
   [:proposicao-id :string]
   [:tipo :string]
   [:ano :int]
   [:sequencial :int]
   [:urn-lex :string]
   [:ementa :string]
   [:autor-tipo {:optional true} [:maybe :string]]
   [:autor-texto {:optional true} [:maybe :string]]
   [:estado :string]
   [:norma {:optional true} [:maybe wire-norma/NormaOut]]])

