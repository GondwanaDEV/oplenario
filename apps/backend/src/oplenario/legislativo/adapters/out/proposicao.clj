(ns oplenario.legislativo.adapters.out.proposicao)

;; ADAPTER OUT (gate de SAIDA): model -> wire/out. Projeta + FILTRA campos sensiveis no que
;; sai (resposta HTTP / payload de evento emitido). A defesa anti-vazamento (ex.: CPF) mora AQUI.
