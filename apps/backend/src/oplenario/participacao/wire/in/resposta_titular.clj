(ns oplenario.participacao.wire.in.resposta-titular
  "Representacao EXTERNA de ENTRADA da RESPOSTA a uma solicitacao do titular (§22.10 wire/in, ADR-0001) — o corpo
  que o SERVIDOR/Encarregado envia ao RESPONDER (POST /lgpd/solicitacoes/:id/resposta). So `corpo` (texto da
  resposta). respondido-por/respondida-em INJETADOS do ator/relogio, NUNCA do corpo. Map CLOSED — chave a mais
  e' rejeitada (fail-closed 400).")

(def ResponderTitular
  "Corpo de resposta a uma solicitacao do titular. Closed: allowlist estrita (so `corpo`). O teto (:max 50000)
  espelha a CHECK de tamanho da mig 0041."
  [:map {:closed true}
   [:corpo [:string {:min 1 :max 50000}]]])
