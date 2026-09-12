(ns oplenario.legislativo.wire.out.meu-painel
  "Representacao EXTERNA de SAIDA do painel do vereador (§22.10 wire/out, ADR-0001, Onda C1).")

(def ProposicaoResumoMeuPainelOut
  [:map {:closed true}
   [:id :string]
   [:tipo :string]
   [:ano :int]
   [:sequencial :int]
   [:urn-lex :string]
   [:ementa :string]
   [:estado :string]
   [:atualizado-em :string]])

(def ParecerResumoMeuPainelOut
  [:map {:closed true}
   [:id :string]
   [:objeto-tipo :string]
   [:objeto-id :string]
   [:comissao-id :string]
   [:estado :string]
   [:voto-relator [:maybe :string]]
   [:criado-em :string]])

(def CienciaPendenteOut
  "Um parecer PUBLICADO sobre proposicao de minha autoria, ainda nao acusado. `parecer-id` e' o
  `evento-ref` a mandar em POST /meu/ciencias."
  [:map {:closed true}
   [:parecer-id :string]
   [:proposicao-id :string]
   [:tipo :string]
   [:ano :int]
   [:sequencial :int]
   [:urn-lex :string]
   [:ementa :string]])

(def MeuPainelOut
  "A resposta de GET /meu/painel (Onda C1, §11.2/§11.3; +vereador-id Onda C3 — bootstrap de identidade p/
  o cockpit ao vivo interpretar o placar/presenca compartilhados, ambos keyed por vereador-id).

  `<lista>-truncado` (booleano, um por lista — frente 'truncamento-familia'): as 3 listas cortam num teto
  server-side (`teto-meu-painel`, PRIVADO em `components/repositorio`, nunca exposto aqui — regra 1 da
  familia) sem paginacao nesta fatia; mesma sonda teto+1 ja' usada por `wire/out/ficha-materia` — sem 5a
  forma, sem `count(*)` novo. `:ciencias-truncado` e' o mais grave dos 3: cada `parecer-id` de `:ciencias`
  e' o `evento-ref` que POST /meu/ciencias exige, e o FE so' obtem esse id POR AQUI — uma ciencia cortada
  e' uma ciencia que o vereador nao tem como dar."
  [:map {:closed true}
   [:vereador-id {:optional true} [:maybe :string]]
   [:proposicoes [:sequential ProposicaoResumoMeuPainelOut]]
   [:proposicoes-truncado :boolean]
   [:pareceres [:sequential ParecerResumoMeuPainelOut]]
   [:pareceres-truncado :boolean]
   [:ciencias [:sequential CienciaPendenteOut]]
   [:ciencias-truncado :boolean]])

(def AcusarCienciaOut
  "A resposta de POST /meu/ciencias — o recibo append-only (Inv.10)."
  [:map {:closed true}
   [:id :string]
   [:ciente-em :string]])
