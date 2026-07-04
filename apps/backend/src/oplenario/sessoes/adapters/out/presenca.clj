(ns oplenario.sessoes.adapters.out.presenca
  "Gate de SAIDA `models -> wire/out` da PRESENCA (§22.10 adapters/out, ADR-0001 §3) — eixo C. Projeta o recibo
  do registro de presenca p/ a borda. Validado contra o contrato wire/out (drift de campo = bug de servidor ->
  500, nunca resposta malformada que envenena o codegen do front, Eixo 8)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.sessoes.wire.out :as wire]))

(set! *warn-on-reflection* true)

(defn recibo-presenca->wire
  "Recibo de dominio {:id uuid} -> PresencaReciboOut (validado, resposta 201)."
  [{:keys [id]}]
  (let [out {:id (some-> id str)}]
    (when-not (m/validate wire/PresencaReciboOut out)
      (throw (ex-info "recibo de presenca viola o contrato PresencaReciboOut (bug de servidor)"
                      {:campos (keys (me/humanize (m/explain wire/PresencaReciboOut out)))})))
    out))

(defn resumo-presenca->wire
  "Resumo cru (kebab, do db) -> PresencaResumoOut (validado)."
  [{:keys [media-percentual sessoes-consideradas membros-da-casa]}]
  (let [out {:media-percentual media-percentual :sessoes-consideradas sessoes-consideradas
             :membros-da-casa membros-da-casa}]
    (when-not (m/validate wire/PresencaResumoOut out)
      (throw (ex-info "resumo de presenca viola o contrato PresencaResumoOut (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/PresencaResumoOut out))})))
    out))
