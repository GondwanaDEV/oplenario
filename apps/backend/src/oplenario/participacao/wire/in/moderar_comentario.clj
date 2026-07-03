(ns oplenario.participacao.wire.in.moderar-comentario
  "Representacao EXTERNA de ENTRADA da MODERACAO de um comentario (§22.10 wire/in, ADR-0001) — o corpo que o
  SERVIDOR envia ao decidir (POST /comentarios/:id/moderar). `acao` (aprovado|rejeitado — NUNCA 'pendente':
  moderar so' DECIDE um desfecho) + `motivo-rejeicao` opcional no schema (a OBRIGATORIEDADE condicional a
  `acao=rejeitado` + a validacao contra o vocabulario FIXO dos 5 motivos vivem no adapters/in, nao aqui —
  Malli estatico nao expressa bem 'obrigatorio SE outro campo = X' sem multi-schema; o projeto prefere a
  checagem manual explicita, mesmo padrao dos demais `str/blank?` deste modulo). moderado-por/moderado-em
  INJETADOS do ator/relogio, NUNCA do corpo.")

(def ModerarComentarioIn
  "Corpo de moderacao de comentario. Closed: allowlist estrita (acao+motivo-rejeicao). O teto (:max 2000)
  espelha o CHECK de tamanho do motivo_rejeicao (comentario/moderacao_comentario, mig 0043)."
  [:map {:closed true}
   [:acao [:enum "aprovado" "rejeitado"]]
   [:motivo-rejeicao {:optional true} [:maybe [:string {:max 2000}]]]])
