(ns oplenario.cadastros.wire.out.vereador
  "Representacao EXTERNA de SAIDA da leitura de vereadores (§22.10 wire/out, ADR-0001, Onda D Slice 3) — o
  contrato de GET /cadastros/vereadores (lista) e GET /cadastros/vereadores/:id (ficha), do qual o Eixo 8
  gera os tipos TS. `estado`/`estado-mandato` ficam :string (nao enum fechado): mesmo racional de
  legislativo/wire/out/proposicao — o ciclo de vida do mandato (cassacao/renuncia/licenca/...) e' dado que
  pode ganhar variantes sem exigir refactor do contrato.")

(def VereadorLinhaOut
  "Uma linha da lista (GET /cadastros/vereadores) — nome + o mandato/cargo-na-Mesa vigentes na data de
  consulta (nil se o vereador nao tem mandato corrente)."
  [:map {:closed true}
   [:id :string]
   [:nome :string]
   [:nome-parlamentar {:optional true} [:maybe :string]]
   [:partido {:optional true} [:maybe :string]]
   [:estado-mandato {:optional true} [:maybe :string]]
   [:cargo-mesa {:optional true} [:maybe :string]]])

(def ComissaoDoVereadorOut
  "Uma comissao (incl. a Mesa Diretora, tipo=\"mesa\") de que o vereador e' membro vigente, com o cargo
  nomeado se houver (presidente/relator/...)."
  [:map {:closed true}
   [:nome :string]
   [:tipo :string]
   [:cargo {:optional true} [:maybe :string]]])

(def MandatoVigenteOut
  "O mandato que cobre a data de consulta, dentro da VereadorFichaOut. Legislatura vem achatada
  (`legislatura-*`) — nao ha' porque expor um sub-objeto pra 3 campos escalares. `cargo-mesa` e' derivado
  pelo adapter a partir de `comissoes` (a entrada de tipo=\"mesa\"), nunca lido do mandato."
  [:map {:closed true}
   [:partido {:optional true} [:maybe :string]]
   [:estado :string]
   [:natureza :string]
   [:posse :string] ; vigencia-inicio ISO
   [:legislatura-numero {:optional true} [:maybe :int]]
   [:legislatura-ano-inicio {:optional true} [:maybe :int]]
   [:legislatura-ano-fim {:optional true} [:maybe :int]]
   [:cargo-mesa {:optional true} [:maybe :string]]])

(def VereadorFichaOut
  "GET /cadastros/vereadores/:id — a ficha completa (nome + mandato vigente com legislatura achatada +
  comissoes vigentes). `:mandato` nil se o vereador nao tem mandato corrente."
  [:map {:closed true}
   [:id :string]
   [:nome :string]
   [:nome-parlamentar {:optional true} [:maybe :string]]
   [:mandato {:optional true} [:maybe MandatoVigenteOut]]
   [:comissoes [:sequential ComissaoDoVereadorOut]]])
