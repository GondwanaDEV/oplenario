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
  "A resposta de GET /meu/painel (Onda C1, §11.2/§11.3)."
  [:map {:closed true}
   [:proposicoes [:sequential ProposicaoResumoMeuPainelOut]]
   [:pareceres [:sequential ParecerResumoMeuPainelOut]]
   [:ciencias [:sequential CienciaPendenteOut]]])

(def AcusarCienciaOut
  "A resposta de POST /meu/ciencias — o recibo append-only (Inv.10)."
  [:map {:closed true}
   [:id :string]
   [:ciente-em :string]])
