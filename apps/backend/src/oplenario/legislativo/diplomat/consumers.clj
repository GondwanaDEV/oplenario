(ns oplenario.legislativo.diplomat.consumers
  "Inbound (§22.10 diplomat/consumers, ADR-0001, Onda E fatia 1): `legislativo` passa a ser consumidor do
  PROPRIO `norma.publicada` — o produtor da notificacao interna 'a sua proposicao virou lei'. Nome de
  consumidor PROPRIO (`legislativo-notificacao`) = dedup independente por (consumidor, key), exatamente
  como `transparencia-portal` x `transparencia-notificacao`: o portal e a inbox drenam o mesmo evento sem
  interferencia.

  O handler recebe o RESOLVEDOR injetado pelo HOST (identidade do vereador) — `legislativo` nunca importa
  `cadastros` (§22.10). O tipo do evento e' STRING LITERAL, nao import de events/ (contrato de fiacao do bus)."
  (:require [oplenario.kernel.outbox :as outbox]
            [oplenario.legislativo.components.repositorio :as repo]))

(def ^:private nome-consumidor "legislativo-notificacao")

(def tipos-consumidos
  "FONTE UNICA dos tipos consumidos por `legislativo` (hoje um so')."
  ["norma.publicada"])

(defn registrar
  "Funde o handler de notificacao num `registro` EXISTENTE. `resolver-identidade-do-vereador` =
  (fn [tx ente-id vereador-id] -> identidade-id | nil), injetada pelo host (sistema.clj)."
  [registro resolver-identidade-do-vereador]
  (let [handler (repo/notificar-autor-da-norma! resolver-identidade-do-vereador)]
    (reduce (fn [r tipo] (outbox/registrar r nome-consumidor tipo handler))
            registro tipos-consumidos)))
