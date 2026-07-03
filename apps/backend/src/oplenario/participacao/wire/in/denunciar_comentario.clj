(ns oplenario.participacao.wire.in.denunciar-comentario
  "Representacao EXTERNA de ENTRADA da DENUNCIA de um comentario (§22.10 wire/in, ADR-0001) — o corpo que o
  CIDADAO envia ao denunciar (POST /portal/comentarios/:id/denunciar). `motivo` e' texto livre OPCIONAL
  (diferente de motivo-rejeicao da moderacao, que e' vocabulario FIXO). denunciante/denunciado-em INJETADOS
  do ator/relogio, NUNCA do corpo.")

(def DenunciarComentarioIn
  "Corpo de denuncia de comentario. Closed: allowlist estrita (so `motivo`, opcional). Teto (:max 2000)
  espelha o CHECK de tamanho da mig 0043."
  [:map {:closed true}
   [:motivo {:optional true} [:maybe [:string {:max 2000}]]]])
