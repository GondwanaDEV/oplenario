(ns oplenario.legislativo.wire.out.documento
  "Representacao EXTERNA de SAIDA do documento gerado (§22.10 wire/out, ADR-0001, Onda B Slice 6) — o
  contrato de GET/POST/PATCH /legislativo/documentos(...). `tipo-documento`/`estado` ficam :string (nunca
  enum fechado): mesmo racional de wire/out/proposicao — o vocabulario vem de legislativo.logic (fonte
  unica), o wire so' o repassa. `protocolo-numero`/`protocolo-ano` sao a projecao ACHATADA (denormalizada) do
  protocolo geral vinculado, quando ha' um (nil ate' 'Protocolar e numerar' acontecer) — o editor nao faz um
  segundo GET so' pra mostrar o numero apos protocolar.")

(def DocumentoOut
  [:map {:closed true}
   [:id :string]
   [:modelo-id :string]
   [:tipo-documento :string]
   [:assunto :string]
   [:corpo :string]
   [:estado :string]
   [:protocolo-geral-id {:optional true} [:maybe :string]]
   [:protocolo-numero {:optional true} [:maybe :int]]
   [:protocolo-ano {:optional true} [:maybe :int]]
   [:lock-version :int]
   [:criado-em :string]])
