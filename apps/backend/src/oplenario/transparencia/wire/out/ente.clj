(ns oplenario.transparencia.wire.out.ente
  "Representacao EXTERNA de SAIDA do perfil publico do ente (§22.10 wire/out, ADR-0001) — so' os campos que
  o portal precisa para exibir o nome real da Casa em vez do UUID da rota (barra institucional/rodape).
  Perfil cadastral (nome oficial/curto) e' informacao publica por natureza — sem PII, sem dado de tenant
  sensivel."
  )

(def EnteOut
  [:map {:closed true}
   [:nome-oficial :string]
   [:nome-curto {:optional true} [:maybe :string]]])
