(ns oplenario.participacao.adapters.in.comentario
  "Gate de ENTRADA `wire/in -> models` do comentario (§22.10 adapters/in, ADR-0001) — chamado SO pelo
  diplomat/. Valida e coage a representacao externa (JSON: strings) p/ o dominio, defendendo a borda
  (fail-closed -> 400 via ex-info :validacao/invalido). ALLOWLIST estrita (so `corpo`): autor/proposicao
  sao INJETADOS do ator/path na borda, NUNCA vem do corpo (anti-forge)."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.participacao.wire.in.comentario :as wire]))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- so-esperados [m campos]
  (reduce (fn [acc k] (cond-> acc (contains? m k) (assoc (keyword k) (get m k)))) {} campos))

(def ^:private campos ["corpo"])

(defn coagir-comentario
  "Corpo JSON {corpo} (chave STRING) -> mapa de dominio {:corpo}. ALLOWLIST descarta campo forjado (estado,
  autor, proposicao_id); o schema CLOSED + o :max 2000 espelham o CHECK da mig 0043."
  [json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON {corpo}" {:campo :corpo}))
  (let [mp (so-esperados json-params campos)]
    (when-let [erros (m/explain wire/ComentarioIn mp)]
      (invalido! "corpo de comentario invalido" {:campos (keys (me/humanize erros))}))
    (when (str/blank? (:corpo mp)) (invalido! "corpo obrigatorio nao pode ser vazio" {:campo :corpo}))
    {:corpo (:corpo mp)}))
