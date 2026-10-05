(ns oplenario.legislativo.wire.out.norma
  "Representacao EXTERNA de SAIDA da NORMA promulgada (§22.10 wire/out, ADR-0001, F3.8b) — o que a tela de
  pos-aprovacao mostra depois de 'Promulgar': numero, URN LexML, e a publicacao quando houver. `ente-id` e
  `promulgado-por` nunca saem (interno de tenant e de quem operou, mesmo racional de wire/out/autografo).
  `lock-version` sai porque o 'Registrar publicacao' o devolve (CAS real, como o retorno do Executivo).")

(def NormaOut
  [:map {:closed true}
   [:id :string]
   [:proposicao-id :string]
   [:tipo-norma :string]
   [:numero :int]
   [:ano :int]
   [:urn :string]
   [:ementa :string]
   [:estado :string]
   [:promulgado-em :string]
   [:publicado-em {:optional true} [:maybe :string]]
   [:veiculo-publicacao {:optional true} [:maybe :string]]
   [:lock-version :int]])
