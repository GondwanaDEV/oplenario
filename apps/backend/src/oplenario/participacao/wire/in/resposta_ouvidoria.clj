(ns oplenario.participacao.wire.in.resposta-ouvidoria
  "Representacao EXTERNA de ENTRADA da RESPOSTA de ouvidoria (§22.10 wire/in, ADR-0001) — o corpo que o
  SERVIDOR envia ao RESPONDER (com merito) uma manifestacao (POST /ouvidoria/manifestacoes/:id/resposta).
  So `corpo` (texto da resposta). respondido-por/respondida-em INJETADOS do ator/relogio, NUNCA do corpo.")

(def RespostaOuvidoriaIn
  "Corpo de resposta de ouvidoria. Closed: allowlist estrita (so `corpo`). O teto (:max 50000) espelha a
  CHECK de tamanho da mig 0042."
  [:map {:closed true}
   [:corpo [:string {:min 1 :max 50000}]]])
