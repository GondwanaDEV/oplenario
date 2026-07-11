(ns oplenario.legislativo.adapters.out.pos-aprovacao
  "Gate de SAIDA `models -> wire/out` do POS-APROVACAO (§22.10 adapters/out, ADR-0001, Onda B Slice 7,
  F3.8a). `pos-aprovacao->wire` REUSA AutografoOut/TramitacaoExecutivaOut JA PROJETADOS (o diplomat chama
  os DOIS adapters/out irmaos antes e compoe aqui — adapters/ nunca chama outro adapters/, ADR-0001 §3,
  mesma disciplina de adapters.out.ficha-materia reusando adapters.out.proposicao/detalhe->wire)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.legislativo.wire.out.pos-aprovacao :as wire]))

(set! *warn-on-reflection* true)

(defn- validado [schema out tipo]
  (when-not (m/validate schema out)
    (throw (ex-info (str "projecao de " tipo " viola o contrato wire/out (bug de servidor)")
                    {:campos (keys (me/humanize (m/explain schema out)))})))
  out)

(defn pos-aprovacao->wire
  "`autografo-out` (AutografoOut JA PROJETADO, ou nil) + `tramitacao-out` (TramitacaoExecutivaOut JA
  PROJETADO, ou nil) -> PosAprovacaoOut."
  [autografo-out tramitacao-out]
  (validado wire/PosAprovacaoOut
            {:autografo autografo-out :tramitacao-executiva tramitacao-out}
            "pos-aprovacao"))
