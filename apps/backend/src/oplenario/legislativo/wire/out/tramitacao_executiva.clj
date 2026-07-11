(ns oplenario.legislativo.wire.out.tramitacao-executiva
  "Representacao EXTERNA de SAIDA da TRAMITACAO NO EXECUTIVO (§22.10 wire/out, ADR-0001, Onda B Slice 7,
  F3.8a) — o processo sancao/veto que evolui. `estado`/`veto-tipo` ficam :string (nunca enum fechado): o
  vocabulario vem de legislativo.logic (fonte unica), o wire so' o repassa (mesmo racional de
  wire/out/documento — o vocabulario e' FIXO nesta vertical, mas a CONVENCAO do modulo e' nunca fechar
  enum na saida). `veto-votacao-id` e' forward-ref (sem FK) — opcional/maybe.")

(def TramitacaoExecutivaOut
  [:map {:closed true}
   [:id :string]
   [:autografo-id :string]
   [:estado :string]
   [:veto-tipo {:optional true} [:maybe :string]]
   [:veto-razoes {:optional true} [:maybe :string]]
   [:veto-votacao-id {:optional true} [:maybe :string]]
   [:respondido-em {:optional true} [:maybe :string]]
   [:apreciado-em {:optional true} [:maybe :string]]
   [:lock-version :int]])
