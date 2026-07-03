(ns oplenario.participacao.wire.in.recurso-esic
  "Representacao EXTERNA de ENTRADA do RECURSO e-SIC (§22.10 wire/in, ADR-0001) — o que o CIDADAO envia no
  corpo de POST /portal/esic/pedidos/:id/recursos. So o campo que o cidadao controla: `motivo` (texto livre).
  O pedido recorrido vem do :id do path; o solicitante/recibo/instancia sao INJETADOS na borda, NUNCA do
  corpo (anti-forge). Map CLOSED — chave a mais e' rejeitada (fail-closed 400 no adapter).")

(def InterporRecurso
  "Corpo de interposicao de recurso e-SIC. Closed: allowlist estrita (so `motivo`). O teto (:max 20000)
  espelha a CHECK de tamanho da mig 0040 (defesa-em-profundidade anti-abuso)."
  [:map {:closed true}
   [:motivo [:string {:min 1 :max 20000}]]])
