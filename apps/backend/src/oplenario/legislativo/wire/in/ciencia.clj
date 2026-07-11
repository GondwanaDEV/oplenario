(ns oplenario.legislativo.wire.in.ciencia
  "Representacao EXTERNA de ENTRADA da ciencia do vereador (§22.10 wire/in, ADR-0001, Onda C1). `:closed
  true` recusa campo extra; `vereador-id` NUNCA vem do corpo (anti-forja — sempre resolvido do ator pelo
  host, §22.5.3, mesmo contrato de `meu-painel`).")

(def AcusarCiencia
  "Corpo de POST /meu/ciencias. `evento-ref` = o id do evento reconhecido (V1: o id do parecer publicado
  sobre proposicao de minha autoria); `tipo` = o vocabulario aberto do evento (V1: so' 'parecer_publicado')."
  [:map {:closed true}
   [:evento-ref [:string {:min 1 :max 36}]]
   [:tipo [:string {:min 1 :max 100}]]])
