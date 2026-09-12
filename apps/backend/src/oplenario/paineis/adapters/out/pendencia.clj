(ns oplenario.paineis.adapters.out.pendencia
  "Gate de SAIDA `models -> wire/out` das pendencias (§22.10 adapters/out, ADR-0001) — chamado SO pelo
  diplomat/. Projeta o read-model do dominio (pendencias abertas + total real, kebab) p/ a representacao
  externa (strings) e FILTRA o tenant (ente-id), que nunca vaza. Validada contra wire/out.OQueVenceOut
  (drift de campo = bug de servidor -> 500, nunca resposta malformada)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.paineis.wire.out.pendencia :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- pendencia->wire [p]
  {:objeto-tipo (:objeto-tipo p) :objeto-id (->str (:objeto-id p)) :protocolo (:protocolo p)
   :vence-em (->str (:vence-em p)) :estado (:estado p)})

(defn o-que-vence->wire
  "Read-model do painel {:pendencias [...] :pendencias-total N} -> OQueVenceOut (validada). `pendencias-total`
  passa direto (ja' e' um inteiro do Repo — nenhuma transformacao de saida)."
  [{:keys [pendencias pendencias-total]}]
  (let [out {:pendencias (mapv pendencia->wire pendencias) :pendencias-total pendencias-total}]
    (when-not (m/validate wire/OQueVenceOut out)
      (throw (ex-info "projecao do painel de pendencias viola o contrato OQueVenceOut (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/OQueVenceOut out))})))
    out))
