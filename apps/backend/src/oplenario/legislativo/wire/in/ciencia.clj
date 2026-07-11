(ns oplenario.legislativo.wire.in.ciencia
  "Representacao EXTERNA de ENTRADA da ciencia do vereador (§22.10 wire/in, ADR-0001, Onda C1). `:closed
  true` recusa campo extra; `vereador-id` NUNCA vem do corpo (anti-forja — sempre resolvido do ator pelo
  host, §22.5.3, mesmo contrato de `meu-painel`).")

(def AcusarCiencia
  "Corpo de POST /meu/ciencias. `evento-ref` = o id do evento reconhecido (V1: o id do parecer publicado
  sobre proposicao de minha autoria); `tipo` = vocabulario FECHADO (review sec MINOR — so' 'parecer_publicado'
  na V1, mesma disciplina de vocabulario fechado do §22.7; `ciencia_vereador.tipo` no banco continua `text`
  aberto, a extensibilidade futura e' adicionar valores aqui, nao abrir o schema de borda)."
  [:map {:closed true}
   [:evento-ref [:string {:min 1 :max 36}]]
   [:tipo [:enum "parecer_publicado"]]])
