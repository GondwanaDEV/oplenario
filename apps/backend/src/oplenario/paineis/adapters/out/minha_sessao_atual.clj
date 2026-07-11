(ns oplenario.paineis.adapters.out.minha-sessao-atual
  "Gate de SAIDA `models -> wire/out` de GET /meu/sessao-atual (§22.10 adapters/out, ADR-0001, Onda C3). A
  `situacao` e' DERIVADA (mesma `paineis.logic.situacao/derivar` que `adapters/out/sli-sessao` usa — fonte
  unica do rotulo de negocio, review clojure MEDIUM daquele slice) a partir do `estado-atual` cru da
  PRIMEIRA entrada de `sli-sessoes` (ja' ordenada 'em curso primeiro' pelo Repo)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.paineis.logic.situacao :as situacao]
            [oplenario.paineis.wire.out.minha-sessao-atual :as wire]))

(set! *warn-on-reflection* true)

(defn minha-sessao-atual->wire
  "Sequencia de sessoes (cru, do controller sli-sessoes — MESMO shape que `sli-sessoes->wire` consome, uma
  seq PLANA, nao um mapa {:sessoes ...}) -> MinhaSessaoAtualOut (validado). So' a PRIMEIRA entrada (ja
  ordenada 'em curso primeiro'); lista vazia -> {:sessao-id nil :situacao nil}."
  [sessoes]
  (let [primeira (first sessoes)
        out {:sessao-id (some-> primeira :sessao-id str)
             :situacao (some-> primeira :estado-atual situacao/derivar)}]
    (when-not (m/validate wire/MinhaSessaoAtualOut out)
      (throw (ex-info "projecao de minha-sessao-atual viola o contrato (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/MinhaSessaoAtualOut out))})))
    out))
