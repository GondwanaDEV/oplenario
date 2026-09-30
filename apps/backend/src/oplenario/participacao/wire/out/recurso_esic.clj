(ns oplenario.participacao.wire.out.recurso-esic
  "Representacao EXTERNA de SAIDA do RECURSO e-SIC (§22.10 wire/out, ADR-0001) — os contratos de borda que o
  `adapters/out` produz. Tudo JSON-serializavel (uuid/Instant viram string). NAO expoe o tenant (ente-id),
  o pedido_id nem ids internos alem do necessario — a defesa anti-vazamento mora no adapters/out.")

(def RecursoReciboOut
  "O RECIBO da interposicao do recurso (resposta 201 de POST /portal/esic/pedidos/:id/recursos): a prova do
  inicio do relogio PROPRIO do recurso. So o protocolo (chave publica) + o instante do recibo. Sem PII, sem id."
  [:map {:closed true}
   [:protocolo :string]
   [:recibo-em :string]
   ;; ADR-0018 (Eixo 2): a Casa esta' com o sistema restrito — o pedido foi recebido e o prazo corre; o recibo diz
   ;; desde quando (so' a data; o motivo nao e' publico). Ausente numa Casa ativa.
   [:acesso-restrito-desde {:optional true} :string]])

(def DecisaoReciboOut
  "O recibo da DECISAO do recurso (resposta 200 de POST /esic/recursos/:id/decisao): o instante em que a
  autoridade decidiu. So o carimbo do desfecho."
  [:map {:closed true}
   [:decidido-em :string]])
