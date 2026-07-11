(ns oplenario.legislativo.wire.out.pos-aprovacao
  "Representacao EXTERNA de SAIDA da leitura COMPOSTA do POS-APROVACAO (§22.10 wire/out, ADR-0001, Onda B
  Slice 7, F3.8a) — o contrato de GET /legislativo/proposicoes/:id/pos-aprovacao (e o corpo 201 de POST
  .../autografo). Reusa AutografoOut/TramitacaoExecutivaOut (mesma disciplina de wire/out/ficha-materia
  reusando ProposicaoDetalheOut — nao duplica os campos). Ambos os campos sao OPCIONAIS/maybe: `autografo`
  nil ate' 'Gerar autografo e enviar ao Executivo' acontecer; `tramitacao-executiva` nil so' no instante
  teorico entre gerar! e iniciar! (que na pratica nunca aparece por fora — Repo/
  gerar-autografo-e-abrir-tramitacao! compoe os dois NUMA UNICA tx)."
  (:require [oplenario.legislativo.wire.out.autografo :as autografo]
            [oplenario.legislativo.wire.out.tramitacao-executiva :as tramitacao-executiva]))

(def PosAprovacaoOut
  [:map {:closed true}
   [:autografo {:optional true} [:maybe autografo/AutografoOut]]
   [:tramitacao-executiva {:optional true} [:maybe tramitacao-executiva/TramitacaoExecutivaOut]]])
