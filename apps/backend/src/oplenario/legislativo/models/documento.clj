(ns oplenario.legislativo.models.documento
  "Representacao INTERNA (dominio) do DOCUMENTO gerado — Malli (§22.10 models/, F3.9b). Merge do dominio no
  template; state machine rascunho -> emitido (emitido congela). `protocolo-geral-id` = vinculo opcional ao
  Protocolo Geral (F3.9a). Enums de legislativo.logic; carimbos via kernel.malli/Instante."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.legislativo.logic :as logic]))

(defn- enum-de [s] (into [:enum] (sort s)))

(def Documento
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:modelo-id :uuid]
   [:tipo-documento (enum-de logic/tipos-documento)]
   [:assunto :string]
   [:corpo :string]
   [:estado (enum-de logic/estados-documento)]
   [:protocolo-geral-id {:optional true} [:maybe :uuid]]
   [:emitido-em {:optional true} [:maybe km/Instante]]
   [:emitido-por {:optional true} [:maybe :uuid]]
   [:lock-version :int]])
