(ns oplenario.participacao.adapters.out.esic-cumprimento
  "Gate de SAIDA `models -> wire/out` do cumprimento e-SIC (§22.10 adapters/out, ADR-0001, FE Onda A1)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.participacao.wire.out.esic-cumprimento :as wire]))

(set! *warn-on-reflection* true)

(defn esic-cumprimento->wire
  "{:total-encerrados :cumpridos-no-prazo} (cru) -> EsicCumprimentoOut (validado). `percentual` DERIVADO
  aqui (arredondado), nil se total-encerrados=0."
  [{:keys [total-encerrados cumpridos-no-prazo]}]
  (let [out {:total-encerrados total-encerrados
             :cumpridos-no-prazo cumpridos-no-prazo
             :percentual (when (pos? total-encerrados)
                          (int (Math/round (* 100.0 (/ cumpridos-no-prazo total-encerrados)))))}]
    (when-not (m/validate wire/EsicCumprimentoOut out)
      (throw (ex-info "cumprimento e-SIC viola o contrato EsicCumprimentoOut (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/EsicCumprimentoOut out))})))
    out))
