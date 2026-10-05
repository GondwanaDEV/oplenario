(ns oplenario.legislativo.adapters.out.meus-votos
  "Gate de SAIDA `models -> wire/out` dos votos do proprio vereador (§22.10 adapters/out, ADR-0001)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.legislativo.wire.out.meus-votos :as wire]))

(set! *warn-on-reflection* true)

(defn- voto->wire
  [{:keys [votacao-id voto registrado-em anulada portal materia-tipo materia-ano materia-sequencial materia-ementa]}]
  {:votacao-id (str votacao-id) :voto voto :registrado-em (str registrado-em)
   :anulada anulada :portal portal
   :materia-tipo materia-tipo :materia-ano (some-> materia-ano int)
   :materia-sequencial (some-> materia-sequencial int) :materia-ementa materia-ementa})

(defn meus-votos->wire
  "{:vereador-id :votos :votos-total :votos-por-opcao} (cru, kebab, do controller) -> MeusVotosOut (validado)."
  [{:keys [vereador-id votos votos-total votos-por-opcao]}]
  (let [out {:vereador-id (some-> vereador-id str)
             :votos (mapv voto->wire votos)
             :votos-total votos-total
             :votos-por-opcao votos-por-opcao}]
    (when-not (m/validate wire/MeusVotosOut out)
      (throw (ex-info "votos do vereador violam o contrato MeusVotosOut (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/MeusVotosOut out))})))
    out))
