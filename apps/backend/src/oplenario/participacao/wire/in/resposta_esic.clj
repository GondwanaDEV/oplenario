(ns oplenario.participacao.wire.in.resposta-esic
  "Representacao EXTERNA de ENTRADA da RESPOSTA e-SIC (§22.10 wire/in, ADR-0001) — o corpo que o SERVIDOR
  envia ao RESPONDER um pedido (POST /esic/pedidos/:id/resposta) OU DECIDIR um recurso (POST
  /esic/recursos/:id/decisao). Ambos os atos carregam a MESMA forma de borda: so `corpo` (texto da
  resposta/decisao) — o mesmo schema serve as duas rotas (DRY; os nomes ResponderPedido/DecidirRecurso do
  plano sao o MESMO contrato). respondido-por/respondida-em INJETADOS do ator/relogio, NUNCA do corpo.")

(def RespostaEsicIn
  "Corpo de resposta/decisao e-SIC. Closed: allowlist estrita (so `corpo`). O teto (:max 50000) espelha a
  CHECK de tamanho da mig 0040. Serve ResponderPedido (resposta ao pedido) E DecidirRecurso (decisao do
  recurso) — a forma e' identica; o ALVO (pedido vs recurso) e' decidido pela ROTA, nao pelo corpo."
  [:map {:closed true}
   [:corpo [:string {:min 1 :max 50000}]]])
