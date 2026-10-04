(ns oplenario.participacao.wire.in.indeferimento
  "Representacao EXTERNA de ENTRADA do INDEFERIMENTO fundamentado (§22.10 wire/in, ADR-0001) — o corpo que o SERVIDOR
  envia ao INDEFERIR um pedido e-SIC (POST /esic/pedidos/:id/indeferir, LAI art. 11 §1º II) OU uma solicitacao do
  titular LGPD (POST /lgpd/solicitacoes/:id/indeferir, art. 18 §4º). As duas rotas carregam a MESMA forma de borda: so'
  `fundamentacao` (texto livre, OBRIGATORIO — a lei exige que a recusa indique as razoes; sem taxonomia de hipoteses
  legais, que depende de regulamento local). indeferido-por/indeferido-em INJETADOS do ator/relogio, NUNCA do corpo.")

(def IndeferimentoIn
  "Corpo do indeferimento. Closed: allowlist estrita (so' `fundamentacao`). O teto (:max 50000) e' o MESMO do `corpo`
  da resposta (a fundamentacao e' gravada como a resposta: mig 0040 `resposta_esic.corpo` e mig 0041
  `resposta_titular.corpo`). Serve e-SIC e LGPD — o ALVO e' decidido pela ROTA, nao pelo corpo."
  [:map {:closed true}
   [:fundamentacao [:string {:min 1 :max 50000}]]])
