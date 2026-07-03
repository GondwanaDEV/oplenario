(ns oplenario.participacao.wire.in.prorrogar-ouvidoria
  "Representacao EXTERNA de ENTRADA da PRORROGACAO de ouvidoria (§22.10 wire/in, ADR-0001) — o corpo que o
  SERVIDOR envia ao PRORROGAR o prazo de uma manifestacao (POST /ouvidoria/manifestacoes/:id/prorrogar). So
  `justificativa` (texto livre, OBRIGATORIO — Lei 13.460 art. 10 exige justificativa p/ prorrogar). As datas
  (de/para) sao CALCULADAS na borda (logic/vence-prorrogado-ouvidoria), nunca vem do corpo.")

(def ProrrogarOuvidoriaIn
  "Corpo de prorrogacao de ouvidoria. Closed: allowlist estrita (so `justificativa`). O teto (:max 5000)
  espelha a CHECK de tamanho de participacao.prorrogacao (mig 0042)."
  [:map {:closed true}
   [:justificativa [:string {:min 1 :max 5000}]]])
