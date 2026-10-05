(ns oplenario.identidade.adapters.out.acessos
  "Gate de SAIDA `dominio -> wire/out` dos acessos concedidos (ADR-0005, adendo). Chamado SO' pelo diplomat/. Validado
  contra wire/out (drift = bug de servidor -> 500)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.identidade.wire.out.acessos :as wire]))

(set! *warn-on-reflection* true)

(defn- validar! [schema out rotulo]
  (when-not (m/validate schema out)
    (throw (ex-info (str "projecao viola o contrato " rotulo " (bug de servidor)")
                    {:erros (me/humanize (m/explain schema out))})))
  out)

(defn- instante [x]
  (some-> x (#(if (instance? java.util.Date %) (.toInstant ^java.util.Date %) %)) str))

(defn acessos->wire
  "[{:identidade-id :nome :papel :concedido-em :revogado-em :revogado-por-nome :motivo}] -> AcessosOut."
  [acessos]
  (validar! wire/AcessosOut
            {:acessos (mapv (fn [a] {:identidade-id (str (:identidade-id a)) :nome (:nome a) :papel (:papel a)
                                     :concedido-em (instante (:concedido-em a))
                                     :revogado-em (instante (:revogado-em a))
                                     :revogado-por-nome (:revogado-por-nome a)
                                     :motivo (:motivo a)})
                            acessos)}
            "AcessosOut"))

(defn revogacao->wire
  "{:revogado? :vinculos-encerrados} -> RevogacaoOut."
  [{:keys [revogado? vinculos-encerrados]}]
  (validar! wire/RevogacaoOut {:revogado (boolean revogado?) :vinculo-encerrado (pos? (or vinculos-encerrados 0))}
            "RevogacaoOut"))
