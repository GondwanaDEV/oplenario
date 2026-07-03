(ns oplenario.participacao.wire.out.denuncia-comentario
  "Representacao EXTERNA de SAIDA da DENUNCIA de um comentario (§22.10 wire/out, ADR-0001) — a resposta 200
  de POST /portal/comentarios/:id/denunciar. So a confirmacao — a mesma resposta em cima da 1a denuncia e de
  qualquer repeticao idempotente (nao revela se ja' havia sido denunciado antes por este cidadao).")

(def ReciboOut
  "Confirmacao da denuncia."
  [:map {:closed true}
   [:denunciado :boolean]])
