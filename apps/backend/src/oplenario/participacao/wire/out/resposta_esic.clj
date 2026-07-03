(ns oplenario.participacao.wire.out.resposta-esic
  "Representacao EXTERNA de SAIDA da RESPOSTA e-SIC (§22.10 wire/out, ADR-0001). O recibo da resposta ao
  pedido (200) + a VIEW da resposta para o cidadao. A view NAO vaza `respondido-por` (qual servidor respondeu),
  ente-id nem ids internos alem do necessario — so o conteudo publico (o corpo da resposta + quando). A defesa
  anti-vazamento mora no adapters/out.")

(def RespostaReciboOut
  "Recibo da resposta ao pedido (resposta 200 de POST /esic/pedidos/:id/resposta): so o instante da resposta."
  [:map {:closed true}
   [:respondida-em :string]])

(def RespostaOut
  "View publica de UMA resposta e-SIC (o conteudo que o cidadao le). Filtra `respondido-por`/ente/ids: so o
  corpo da resposta + quando foi respondida. (Consumidor do texto = conteudo do usuario: escapar antes de
  renderizar como HTML — React/Next escapa por padrao.)"
  [:map {:closed true}
   [:corpo :string]
   [:respondida-em :string]])
