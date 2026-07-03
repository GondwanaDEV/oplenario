(ns oplenario.participacao.wire.in.arquivar-ouvidoria
  "Representacao EXTERNA de ENTRADA do ARQUIVAMENTO de ouvidoria (§22.10 wire/in, ADR-0001) — o corpo que o
  SERVIDOR envia ao ARQUIVAR (sem merito) uma manifestacao (POST /ouvidoria/manifestacoes/:id/arquivar). So
  `motivo` (texto livre, OBRIGATORIO — justificativa do arquivamento). arquivado-por/arquivada-em INJETADOS
  do ator/relogio, NUNCA do corpo.")

(def ArquivarOuvidoriaIn
  "Corpo de arquivamento de ouvidoria. Closed: allowlist estrita (so `motivo`). O teto (:max 50000) espelha
  a CHECK de tamanho da resposta_ouvidoria (mig 0042 — o motivo e' persistido nesta mesma tabela append-only)."
  [:map {:closed true}
   [:motivo [:string {:min 1 :max 50000}]]])
