(ns oplenario.participacao.wire.out.resposta-ouvidoria
  "Representacao EXTERNA de SAIDA dos atos administrativos sobre a manifestacao de ouvidoria (§22.10
  wire/out, ADR-0001) — os recibos de responder/arquivar/prorrogar. Tudo JSON-serializavel (Instant/
  LocalDate viram string). NAO expoe o tenant nem ids internos alem do necessario.")

(def RespostaReciboOut
  "Recibo da resposta (200 de POST /ouvidoria/manifestacoes/:id/resposta): so o instante da resposta."
  [:map {:closed true}
   [:respondida-em :string]])

(def ArquivarReciboOut
  "Recibo do arquivamento (200 de POST /ouvidoria/manifestacoes/:id/arquivar): so o instante do arquivamento."
  [:map {:closed true}
   [:arquivada-em :string]])

(def ProrrogarReciboOut
  "Recibo da prorrogacao (200 de POST /ouvidoria/manifestacoes/:id/prorrogar): a nova data de vencimento
  (prorrogado_ate) — o 'anel' do cidadao ja reflete isso no proximo acompanhar."
  [:map {:closed true}
   [:prorrogado-ate :string]])
