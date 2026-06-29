(ns oplenario.sessoes.models.pauta
  "Representacao INTERNA (dominio) da PAUTA (§22.6 eixo B, F4.2a) — Malli (§22.10 models/). PautaSessao
  (container 1:1 com a sessao), PautaItem (mutavel: fase + tipo com FK declarativa + ordem + ativo) e
  PautaAlteracao (log append-only). Enums de sessoes.logic (fonte unica; os CHECK da mig 0027 espelham).
  `proposicao-id` e' forward-ref a legislativo (uuid, sem FK, §22.10)."
  (:require [clojure.string :as str]
            [oplenario.kernel.malli :as km]
            [oplenario.sessoes.logic :as logic]))

(defn- enum-de [s] (into [:enum] (sort s)))

;; container INSERT-only (sem lock_version; review DB-M1).
(def PautaSessao
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:sessao-id :uuid]])

(def PautaItem
  ;; o :fn captura a FK declarativa por tipo (espelha o CHECK pauta_item_proposicao_coerente; review MAJOR-2),
  ;; deixando o model honesto p/ servir de guard de entrada no wire/in (carry do F4).
  [:and
   [:map {:closed true}
    [:ente-id :uuid]
    [:id :uuid]
    [:pauta-sessao-id :uuid]
    [:fase (enum-de logic/fases-pauta)]
    [:tipo-item (enum-de logic/tipos-item-pauta)]
    [:proposicao-id {:optional true} [:maybe :uuid]]
    [:texto-descricao {:optional true} [:maybe :string]]
    [:ordem :int]
    [:ativo :boolean]
    [:lock-version :int]]
   [:fn {:error/message "proposicao exige proposicao-id (e sem descricao); demais tipos exigem texto-descricao nao-vazio"}
    (fn [{:keys [tipo-item proposicao-id texto-descricao]}]
      (if (logic/item-requer-proposicao? tipo-item)
        (and (some? proposicao-id) (nil? texto-descricao))
        (and (nil? proposicao-id) (string? texto-descricao) (not (str/blank? texto-descricao)))))]])

(def PautaAlteracao
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:pauta-sessao-id :uuid]
   [:pauta-item-id {:optional true} [:maybe :uuid]]
   [:tipo (enum-de logic/tipos-alteracao-pauta)]
   [:justificativa {:optional true} [:maybe :string]]
   [:registrado-em km/Instante]])

;; F4.2b — versao canonica (snapshot append-only da pauta num instante). `snapshot` = itens congelados.
(def PautaVersao
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:pauta-sessao-id :uuid]
   [:numero-versao :int]
   [:tipo-versao (enum-de logic/tipos-versao-pauta)]
   [:publica :boolean]
   [:snapshot [:sequential [:map-of :keyword :any]]]
   [:justificativa {:optional true} [:maybe :string]]
   [:publicado-em km/Instante]])
