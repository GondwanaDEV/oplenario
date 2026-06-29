(ns oplenario.sessoes.models.sessao
  "Representacao INTERNA (dominio) da SESSAO plenaria — Malli (§22.10 models/, §22.6 eixo A). Capabilities
  como atributos booleanos; enums de sessoes.logic (fonte unica; os CHECK da mig 0026 espelham). Carimbos via
  kernel.malli/Instante. `sessao-legislativa-id` e' forward-ref a cadastros (uuid, sem FK)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.sessoes.logic :as logic]))

(defn- enum-de [s] (into [:enum] (sort s)))

(def Sessao
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:sessao-legislativa-id :uuid]
   [:tipo-sessao (enum-de logic/tipos-sessao)]
   [:numero-sequencial :int]
   [:estado (enum-de logic/estados-sessao)]
   [:modalidade (enum-de logic/modalidades-sessao)]
   ;; capabilities (§22.6 eixo A)
   [:delibera :boolean]
   [:transmite-publica :boolean]
   [:gera-ata-regimental :boolean]
   [:permite-voto-secreto :boolean]
   [:permite-modalidade-remota :boolean]
   ;; marcos temporais
   [:agendada-para {:optional true} [:maybe km/Instante]]
   [:aberta-em {:optional true} [:maybe km/Instante]]
   [:encerrada-em {:optional true} [:maybe km/Instante]]
   [:motivo-nao-realizada {:optional true} [:maybe :string]]
   [:lock-version :int]])
