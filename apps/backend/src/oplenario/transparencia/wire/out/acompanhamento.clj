(ns oplenario.transparencia.wire.out.acompanhamento
  "Representacao EXTERNA de SAIDA do acompanhamento (§22.10 wire/out, ADR-0001). Tudo JSON-serializavel
  (Instant vira string). ReciboOut = a resposta de seguir/deixar de seguir (so' o estado — o cliente ja sabe
  qual materia). MinhaMateriaOut = item da lista 'minhas materias acompanhadas' (cabecalho publico da materia
  + quando foi seguida). SEM PII: o seguidor e' o proprio ator (nao se expoe identidade de terceiros)."
  )

(def ReciboOut
  [:map {:closed true}
   [:estado [:enum "ativo" "cancelado"]]])

(def MinhaMateriaOut
  [:map {:closed true}
   [:proposicao-id :string]
   [:tipo :string]
   [:ano :int]
   [:sequencial :int]
   [:urn-lex :string]
   [:ementa :string]
   [:estado :string]
   [:seguido-em :string]])
