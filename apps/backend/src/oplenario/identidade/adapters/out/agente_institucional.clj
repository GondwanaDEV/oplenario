(ns oplenario.identidade.adapters.out.agente-institucional
  "Gate de SAIDA `dominio -> wire/out` dos agentes institucionais (B.8, ADR-0013). Validada contra wire/out (drift =
  bug de servidor -> 500)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.identidade.models.identidade :as mod]
            [oplenario.identidade.wire.out.agente-institucional :as wire]))

(set! *warn-on-reflection* true)

(defn- validar! [schema out]
  (when-not (m/validate schema out)
    (throw (ex-info "projecao viola o contrato AgentesInstitucionaisOut (bug de servidor)"
                    {:erros (me/humanize (m/explain schema out))})))
  out)

(defn- instante [x] (some-> x (#(if (instance? java.util.Date %) (.toInstant ^java.util.Date %) %)) str))

(defn agente->out
  "`agente` + a concessao ativa dele (ou nil) -> AgenteInstitucionalOut."
  [agente concessao]
  (let [{:keys [titulo descricao classes]} (get mod/agentes-institucionais agente)]
    {:agente agente :titulo titulo :descricao descricao
     :classes (vec (sort (map name classes)))
     :ligado (some? concessao)
     :ligado-em (instante (:concedida-em concessao))}))

(defn agentes->wire
  "[[agente concessao-ou-nil] ...] -> AgentesInstitucionaisOut."
  [pares]
  (validar! wire/AgentesInstitucionaisOut {:itens (mapv (fn [[a c]] (agente->out a c)) pares)}))
