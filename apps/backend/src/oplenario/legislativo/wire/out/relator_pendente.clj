(ns oplenario.legislativo.wire.out.relator-pendente
  "Representacao EXTERNA de SAIDA da fila de relatores pendentes (§22.10 wire/out, ADR-0001, FE Onda A1).")

(def RelatorPendenteOut
  "Um item da fila. `:tipo`/`:ano`/`:sequencial`/`:urn-lex`/`:ementa` sao `[:maybe ...]` desde a frente
  'truncamento-familia' (achado 'classe JOIN'): `db/parecer/relatores-pendentes` faz LEFT JOIN (nao mais
  INNER) contra `legislativo.proposicoes` (guard ref SEM FK, disc.2) — um parecer cujo `objeto_id` nao
  resolve chega aqui com o cabecalho todo nulo E `:indisponivel true`, em vez de simplesmente sumir da
  fila (mesmo mecanismo de `transparencia/wire/out/acompanhamento`'s MinhaMateriaOut). `:id`/
  `:proposicao-id`/`:criado-em` sao campos PROPRIOS do parecer (nunca nulos, join ou nao)."
  [:map {:closed true}
   [:id :string]
   [:proposicao-id :string]
   [:tipo [:maybe :string]]
   [:ano [:maybe :int]]
   [:sequencial [:maybe :int]]
   [:urn-lex [:maybe :string]]
   [:ementa [:maybe :string]]
   [:criado-em :string]
   [:indisponivel :boolean]])

(def RelatoresPendentesOut
  "A fila 'designar relator' (§16.11 'o que so a Mesa despacha'): pareceres aguardando designação,
  mais antigo primeiro. `:truncado` (frente 'truncamento-familia'): sonda teto+1, sem 5a forma — nao ha'
  contagem barata pre-existente pra' reusar aqui (mesmo racional de `wire/out/ficha-materia`)."
  [:map {:closed true}
   [:itens [:sequential RelatorPendenteOut]]
   [:truncado :boolean]])
