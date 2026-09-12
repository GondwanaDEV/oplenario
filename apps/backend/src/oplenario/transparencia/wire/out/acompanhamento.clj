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
  "Item de 'minhas materias acompanhadas'. O cabecalho (`:tipo`..`:estado`) e' `[:maybe ...]` desde a frente
  'truncamento-familia' (achado 'outra familia', sitio (d)): `meus-da-materia` faz LEFT JOIN (nao mais INNER)
  contra `transparencia.materia` (projecao ASSINCRONA, sem FK) — uma subscricao cuja materia ainda nao foi
  projetada (relay atrasado) ou cuja projecao sumiu chega aqui com o cabecalho todo nulo E
  `:indisponivel true`, em vez de simplesmente sumir da lista."
  [:map {:closed true}
   [:proposicao-id :string]
   [:tipo [:maybe :string]]
   [:ano [:maybe :int]]
   [:sequencial [:maybe :int]]
   [:urn-lex [:maybe :string]]
   [:ementa [:maybe :string]]
   [:estado [:maybe :string]]
   [:seguido-em :string]
   [:indisponivel :boolean]])

(def MeusAcompanhamentosOut
  "Resposta de GET /portal/acompanhamentos (frente 'truncamento-familia', sitio (c)): a lista de subscricoes
  cortava em 200 (`teto-listagem`, `db/acompanhamento.clj`) sem sinalizar. `:acompanhamentos-total` e' o par
  obrigatorio — o teto em si NUNCA sai neste contrato."
  [:map {:closed true}
   [:acompanhamentos [:sequential MinhaMateriaOut]]
   [:acompanhamentos-total :int]])
