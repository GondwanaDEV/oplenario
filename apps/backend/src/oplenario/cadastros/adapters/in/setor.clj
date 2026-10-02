(ns oplenario.cadastros.adapters.in.setor
  "Gate de ENTRADA wire/in -> dominio dos setores (ADR-0020, §22.10 adapters/in). Chamado SO' pelo diplomat/. Valida
  (fail-closed -> :validacao/invalido -> 400) e coage (ids -> UUID). O `id` do setor novo e' gerado aqui; o `ente-id`
  vem do ator, nunca do corpo (§22.5)."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.cadastros.wire.in.setor :as wire]))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- keywordizar [m] (reduce-kv (fn [acc k v] (assoc acc (keyword k) v)) {} m))

(defn- validar! [schema m msg]
  (when-not (map? m) (invalido! "corpo deve ser objeto JSON" {:campos [:corpo]}))
  (let [mm (keywordizar m)]
    (when-let [erros (m/explain schema mm)]
      (invalido! msg {:campos (keys (me/humanize erros))}))
    mm))

(defn- nome! [s]
  (let [n (str/trim (str s))]
    (when (str/blank? n) (invalido! "nome obrigatorio" {:campos [:nome]}))
    n))

(defn criar->dominio [json]
  (let [mm (validar! wire/CriarSetor json "corpo de criar setor invalido")]
    {:id (random-uuid) :nome (nome! (:nome mm))}))

(defn atualizar->dominio [json]
  (let [mm (validar! wire/AtualizarSetor json "corpo de atualizar setor invalido")]
    (when-not (or (contains? mm :nome) (contains? mm :ativo))
      (invalido! "informe nome e/ou ativo" {:campos [:nome :ativo]}))
    (cond-> {}
      (contains? mm :nome) (assoc :nome (nome! (:nome mm)))
      (contains? mm :ativo) (assoc :ativo (:ativo mm)))))

(defn membros->dominio
  "{identidades [..]} -> o conjunto de UUIDs (sem repetidos, em ordem)."
  [json]
  (let [mm (validar! wire/TrocarMembros json "corpo de membros do setor invalido")]
    (into [] (distinct)
          (map #(or (parse-uuid %) (invalido! "identidade invalida" {:campos [:identidades]}))
               (:identidades mm)))))
