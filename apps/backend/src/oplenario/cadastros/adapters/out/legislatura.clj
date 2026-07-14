(ns oplenario.cadastros.adapters.out.legislatura
  "Projecao dominio -> wire de LegislaturaVigenteOut (§22.10 adapters/out). Valida contra o contrato
  (drift = 500, review sec: nunca corpo malformado que envenene o codegen)."
  (:require [malli.core :as m]
            [oplenario.cadastros.wire.out.legislatura :as wire]))

(set! *warn-on-reflection* true)

(defn ->wire [leg]
  (let [w {:id (str (:id leg)) :numero (:numero leg) :ano-inicio (:ano-inicio leg)
           :ano-fim (:ano-fim leg) :vigente (boolean (:vigente leg))}]
    (when-not (m/validate wire/LegislaturaVigenteOut w)
      (throw (ex-info "legislatura-vigente diverge do contrato wire"
                      {:erros (m/explain wire/LegislaturaVigenteOut w)})))
    w))
