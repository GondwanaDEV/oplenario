(ns oplenario.legislativo.wire.out.autografo
  "Representacao EXTERNA de SAIDA do AUTOGRAFO (§22.10 wire/out, ADR-0001, Onda B Slice 7, F3.8a) — o
  artefato legal APPEND-ONLY enviado ao Executivo. `ente-id` NUNCA sai (interno de tenant, mesmo racional
  dos wire/out irmaos). `texto-versao-id`/`destinatario-id` sao forward-refs (sem FK) — opcionais/maybe,
  mesmo tratamento de `protocolo-geral-id` em wire/out/documento.")

(def AutografoOut
  [:map {:closed true}
   [:id :string]
   [:proposicao-id :string]
   [:numero :int]
   [:ano :int]
   [:texto-versao-id {:optional true} [:maybe :string]]
   [:destinatario-texto :string]
   [:destinatario-id {:optional true} [:maybe :string]]
   [:enviado-em :string]
   [:prazo-resposta-em {:optional true} [:maybe :string]]])
