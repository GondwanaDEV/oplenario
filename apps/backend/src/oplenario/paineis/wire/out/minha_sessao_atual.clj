(ns oplenario.paineis.wire.out.minha-sessao-atual
  "Representacao EXTERNA de SAIDA de GET /meu/sessao-atual (§22.10 wire/out, ADR-0001, Onda C3). Reusa a
  MESMA leitura de `sli-sessoes` (paineis/controllers) — so' projeta a PRIMEIRA entrada (a lista ja' vem
  ordenada 'em curso primeiro', mesmo contrato de GET /paineis/sli/sessoes). Sem sessao viva -> ambos nil,
  200 (ausencia de sessao e' um ESTADO, nao um erro).")

(def MinhaSessaoAtualOut
  [:map {:closed true}
   [:sessao-id {:optional true} [:maybe :string]]
   [:situacao {:optional true} [:maybe :string]]])
