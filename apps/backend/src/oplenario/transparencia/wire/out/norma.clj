(ns oplenario.transparencia.wire.out.norma
  "Representacao EXTERNA de SAIDA da norma (§22.10 wire/out, ADR-0001) — contrato que o `adapters/out`
  produz. Tudo JSON-serializavel (Instant vira string). Dado publico por natureza (norma promulgada+
  publicada e' ato legal publico).")

(def NormaOut
  [:map {:closed true}
   [:norma-id :string]
   [:proposicao-id :string]
   [:tipo-norma :string]
   [:numero :int]
   [:ano :int]
   [:urn :string]
   [:ementa :string]
   [:publicado-em :string]
   [:veiculo-publicacao :string]])
