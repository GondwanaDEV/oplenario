(ns oplenario.participacao.wire.in.comentario
  "Representacao EXTERNA de ENTRADA do comentario (§22.10 wire/in, ADR-0001) — o que o CIDADAO envia no
  corpo de POST /portal/materias/:proposicao_id/comentarios. So `corpo` (texto livre) — autor/proposicao_id
  vem do ator/path na borda, NUNCA do corpo (anti-forge). Map CLOSED — chave a mais e' rejeitada.")

(def ComentarioIn
  "Corpo de criacao de comentario. Closed: allowlist estrita (so `corpo`). O teto (:max 2000) espelha o
  CHECK de tamanho da mig 0043 — defesa-em-profundidade anti-abuso."
  [:map {:closed true}
   [:corpo [:string {:min 1 :max 2000}]]])
