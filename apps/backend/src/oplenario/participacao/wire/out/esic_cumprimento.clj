(ns oplenario.participacao.wire.out.esic-cumprimento
  "Representacao EXTERNA de SAIDA do cumprimento e-SIC (§22.10 wire/out, ADR-0001, FE Onda A1).")

(def EsicCumprimentoOut
  "Cumprimento de prazo do e-SIC (§16.11 'o que a Casa entregou'). `percentual` nil quando nao ha
  pedido encerrado ainda (0/0 indefinido)."
  [:map {:closed true}
   [:total-encerrados :int]
   [:cumpridos-no-prazo :int]
   [:percentual [:maybe :int]]])
