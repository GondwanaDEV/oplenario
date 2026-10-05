(ns oplenario.transparencia.adapters.out.vereadores
  "Gate de SAIDA `models -> wire/out` da lista publica dos vereadores em exercicio (§22.10 adapters/out, ADR-0001) —
  chamado SO pelo diplomat/. A linha chega do seam `vereadores-em-exercicio` do host (a do roster de `cadastros`,
  que traz mais do que o portal pode mostrar); aqui se escolhem, uma a uma, as chaves publicas, e o resultado e'
  validado contra `VereadoresOut` (`:closed`): drift de campo = bug de servidor -> 500."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.transparencia.wire.out.vereadores :as wire]))

(set! *warn-on-reflection* true)

(defn- vazio->nil [s]
  (some-> s str str/trim not-empty))

(defn- linha->wire [l]
  {:vereador-id      (str (:vereador-id l))
   :nome-parlamentar (:nome-parlamentar l)
   :nome-civil       (:nome l)
   :partido          (vazio->nil (:partido l))
   :cargo-mesa       (vazio->nil (:cargo-mesa l))})

(defn ->wire
  "Linhas do seam do host ([{:vereador-id :nome :nome-parlamentar :partido :cargo-mesa ...}], ou nil) -> VereadoresOut."
  [linhas]
  (let [out {:vereadores (mapv linha->wire linhas)}]
    (when-not (m/validate wire/VereadoresOut out)
      (throw (ex-info "projecao viola o contrato VereadoresOut (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/VereadoresOut out))})))
    out))
