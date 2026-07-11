(ns oplenario.legislativo.wire.out.protocolo-geral
  "Representacao EXTERNA de SAIDA do PROTOCOLO GERAL (§22.10 wire/out, ADR-0001, Onda B Slice 6) — o
  contrato do 'Livro do Protocolo Geral' que aparece na mesma tela do Expediente, abaixo da bancada de
  geracao (F3.9a, feature 3.23). `objeto-tipo`/`sentido` ficam :string (mesmo racional dos wire/out irmaos —
  vocabulario em legislativo.logic, fonte unica). `objeto-id` e' opcional/maybe: protocolo de papel externo
  (sem objeto interno) tem objeto_id NULL (disc.2). Envelope da lista = `{:itens [...]}` (mesmo padrao dos
  wire/out irmaos), ja' na ordem cronologica que o db/protocolo-geral.clj devolve (por numero, o livro).")

(def ProtocoloGeralOut
  [:map {:closed true}
   [:id :string]
   [:numero :int]
   [:ano :int]
   [:objeto-tipo :string]
   [:objeto-id {:optional true} [:maybe :string]]
   [:sentido :string]
   [:assunto :string]
   [:protocolado-em :string]])

(def LivroProtocoloOut
  [:map {:closed true}
   [:itens [:sequential ProtocoloGeralOut]]])
