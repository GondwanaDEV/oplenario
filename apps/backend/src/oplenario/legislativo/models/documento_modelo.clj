(ns oplenario.legislativo.models.documento-modelo
  "Representacao INTERNA (dominio) do MODELO de documento — Malli (§22.10 models/, F3.9b). Config mutavel do
  tenant; enums de legislativo.logic (fonte unica; os CHECK da mig 0025 espelham)."
  (:require [oplenario.legislativo.logic :as logic]))

(defn- enum-de [s] (into [:enum] (sort s)))

(def DocumentoModelo
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:chave :string]
   [:nome :string]
   [:tipo-documento (enum-de logic/tipos-documento)]
   [:corpo-template :string]
   [:ativo :boolean]
   [:lock-version :int]])
