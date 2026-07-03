(ns oplenario.participacao.wire.out.moderacao-comentario
  "Representacao EXTERNA de SAIDA da MODERACAO de comentarios (§22.10 wire/out, ADR-0001). ReciboOut = a
  resposta 200 de POST /comentarios/:id/moderar (so' id+estado). ItemFilaOut = a linha da fila de moderacao
  (GET /moderacao/comentarios) — rota INTERNA de servidor (exige-papel): AQUI o autor/proposicao PODEM
  aparecer (nao e' rota publica; o moderador precisa ver quem escreveu p/ decidir)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.participacao.logic :as logic]))

(def ReciboOut
  "O recibo da moderacao: id do comentario + o estado a que transicionou. `estado` usa o enum de
  `acoes-moderacao` (aprovado|rejeitado — NUNCA pendente, que nao e' um desfecho de moderacao)."
  [:map {:closed true}
   [:id :string]
   [:estado (km/enum-de logic/acoes-moderacao)]])

(def ItemFilaOut
  "Item da fila de moderacao (SERVIDOR). Inclui autor+proposicao (rota interna, nao publica) + `denunciado`
  (o sinal que faz o item subir na fila)."
  [:map {:closed true}
   [:id :string]
   [:proposicao-id :string]
   [:autor-identidade-id :string]
   ;; CONTEUDO DO USUARIO (texto livre, NAO sanitizado no backend): o consumidor (dashboard interno) DEVE
   ;; escapar antes de renderizar como HTML.
   [:corpo :string]
   [:denunciado :boolean]
   [:criado-em :string]])