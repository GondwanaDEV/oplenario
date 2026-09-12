(ns oplenario.identidade.wire.out.meu-identidade
  "Representacao EXTERNA de SAIDA de GET /meu/identidade (§22.10 wire/out, ADR-0001) — o 'quem sou eu' que
  qualquer ator autenticado (com papel ou sem) le sobre SI MESMO. `:closed true`: so' :nome e :papeis
  cabem aqui — NUNCA :cpf (PII gated em app; ver docstring do ns `identidade.diplomat.http.in`, que
  documenta por que este handler usa `repo/nome-por-id`, nao `identidade-por-id`).")

(def MeuIdentidadeOut
  [:map {:closed true}
   [:nome :string]
   [:papeis [:sequential :string]]])
