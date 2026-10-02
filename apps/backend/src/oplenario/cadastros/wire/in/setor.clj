(ns oplenario.cadastros.wire.in.setor
  "Representacao EXTERNA de ENTRADA dos setores da Casa (ADR-0020 Eixo 1, §22.10 wire/in). `:closed true` recusa campo
  extra (anti-forja: `ente_id` nunca vem do corpo). Ids sao :string no wire — o adapters/in coage para UUID.")

(def CriarSetor
  [:map {:closed true}
   [:nome [:string {:min 1 :max 120}]]])

(def AtualizarSetor
  "PUT parcial: renomeia e/ou ativa/desativa. O adapters/in exige pelo menos um."
  [:map {:closed true}
   [:nome {:optional true} [:string {:min 1 :max 120}]]
   [:ativo {:optional true} :boolean]])

(def TrocarMembros
  "A lotacao INTEIRA (a lista substitui a anterior; vazia esvazia o setor)."
  [:map {:closed true}
   [:identidades [:vector {:max 500} :string]]])
