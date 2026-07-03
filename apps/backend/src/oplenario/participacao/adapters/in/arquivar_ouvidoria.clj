(ns oplenario.participacao.adapters.in.arquivar-ouvidoria
  "Gate de ENTRADA `wire/in -> models` do ARQUIVAMENTO de ouvidoria (§22.10 adapters/in, ADR-0001) —
  chamado SO pelo diplomat/. Coage o corpo {motivo} da rota de SERVIDOR (arquivar manifestacao), fail-closed
  (-> 400). ALLOWLIST estrita (so `motivo`): arquivado-por/arquivada-em INJETADOS do ator/relogio na borda."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.participacao.wire.in.arquivar-ouvidoria :as wire]))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- so-esperados [m campos]
  (reduce (fn [acc k] (cond-> acc (contains? m k) (assoc (keyword k) (get m k)))) {} campos))

(def ^:private campos ["motivo"])

(defn coagir-arquivar
  "Corpo JSON {motivo} (chaves STRING) -> mapa de dominio {:motivo}. ALLOWLIST descarta campo forjado."
  [json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON {motivo}" {:campo :corpo}))
  (let [mp (so-esperados json-params campos)]
    (when-let [erros (m/explain wire/ArquivarOuvidoriaIn mp)]
      (invalido! "corpo de arquivamento de ouvidoria invalido" {:campos (keys (me/humanize erros))}))
    (when (str/blank? (:motivo mp)) (invalido! "motivo obrigatorio nao pode ser vazio" {:campo :motivo}))
    {:motivo (:motivo mp)}))
