(ns oplenario.participacao.adapters.in.encarregado
  "Gate de ENTRADA `wire/in -> models` do ENCARREGADO/DPO (§22.10 adapters/in, ADR-0001) — chamado SO pelo
  diplomat/. Coage o corpo {nome, rotulo, email} da rota de SERVIDOR, fail-closed (-> 400). ALLOWLIST estrita:
  atualizado-por e' INJETADO do ator na borda (anti-forge)."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.participacao.wire.in.encarregado :as wire]))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- so-esperados [m campos]
  (reduce (fn [acc k] (cond-> acc (contains? m k) (assoc (keyword k) (get m k)))) {} campos))

(def ^:private campos ["nome" "rotulo" "email"])

(defn coagir-encarregado
  "Corpo JSON {nome, rotulo, email} (chaves STRING) -> mapa de dominio. ALLOWLIST descarta campo forjado
  (atualizado_por, id, ente_id); schema CLOSED + nao-vazios espelham os CHECK da mig 0041."
  [json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON {nome, rotulo, email}" {:campo :corpo}))
  (let [mp (so-esperados json-params campos)]
    (when-let [erros (m/explain wire/DefinirEncarregado mp)]
      (invalido! "corpo de encarregado invalido" {:campos (keys (me/humanize erros))}))
    (doseq [k [:nome :rotulo :email]]
      (when (str/blank? (get mp k)) (invalido! (str (name k) " obrigatorio nao pode ser vazio") {:campo k})))
    {:nome (:nome mp) :rotulo (:rotulo mp) :email (:email mp)}))
