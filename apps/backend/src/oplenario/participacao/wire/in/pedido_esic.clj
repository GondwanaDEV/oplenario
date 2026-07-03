(ns oplenario.participacao.wire.in.pedido-esic
  "Representacao EXTERNA de ENTRADA do pedido e-SIC (§22.10 wire/in, ADR-0001) — o que o CIDADAO envia no
  corpo de POST /portal/esic/pedidos. So os dois campos que o cidadao controla: `assunto` + `descricao`
  (texto livre = qualquer info publica, e-SIC amplo). O solicitante e o recibo sao INJETADOS do ator/relogio
  na borda, NUNCA vem do corpo (anti-forge). Map CLOSED — chave a mais e' rejeitada (fail-closed no adapter).")

(def PedidoEsicIn
  "Corpo de criacao de pedido e-SIC. Closed: allowlist estrita (assunto+descricao); qualquer outra chave
  (ex.: solicitante_identidade_id forjado, estado) e' rejeitada -> 400. Os tetos (:max) espelham os CHECK de
  tamanho da mig 0039 — defesa-em-profundidade anti-abuso, nao restricao semantica do e-SIC amplo."
  [:map {:closed true}
   [:assunto [:string {:min 1 :max 500}]]
   [:descricao [:string {:min 1 :max 20000}]]])
