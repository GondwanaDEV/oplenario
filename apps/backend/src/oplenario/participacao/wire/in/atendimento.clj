(ns oplenario.participacao.wire.in.atendimento
  "Representacao EXTERNA de ENTRADA do BALCAO interno de atendimento (§22.10 wire/in, ADR-0001): a query das filas
  (GET /atendimento/esic|ouvidoria|lgpd?situacao=...) e o corpo da prorrogacao do e-SIC
  (POST /esic/pedidos/:id/prorrogar — so' `justificativa`; a data e' CALCULADA, LAI art. 11 §2º)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.participacao.logic :as logic]))

(def SituacaoIn
  "O filtro da fila. Ausente = `abertos` (o default e' do adapters/in)."
  (km/enum-de logic/situacoes-do-balcao))

(def ProrrogarPedidoIn
  "Corpo da prorrogacao do pedido e-SIC. Closed: so' `justificativa` (a LAI exige justificativa expressa). O teto
  espelha a CHECK de tamanho de participacao.prorrogacao (mig 0042)."
  [:map {:closed true}
   [:justificativa [:string {:min 1 :max 5000}]]])
