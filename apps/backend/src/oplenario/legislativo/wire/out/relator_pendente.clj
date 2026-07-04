(ns oplenario.legislativo.wire.out.relator-pendente
  "Representacao EXTERNA de SAIDA da fila de relatores pendentes (§22.10 wire/out, ADR-0001, FE Onda A1).")

(def RelatorPendenteOut
  [:map {:closed true}
   [:id :string]
   [:proposicao-id :string]
   [:tipo :string]
   [:ano :int]
   [:sequencial :int]
   [:urn-lex :string]
   [:ementa :string]
   [:criado-em :string]])

(def RelatoresPendentesOut
  "A fila 'designar relator' (§16.11 'o que so a Mesa despacha'): pareceres aguardando designação,
  mais antigo primeiro."
  [:map {:closed true}
   [:itens [:sequential RelatorPendenteOut]]])
