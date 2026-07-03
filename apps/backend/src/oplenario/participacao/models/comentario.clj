(ns oplenario.participacao.models.comentario
  "Representacao INTERNA (dominio) do COMENTARIO de cidadao numa materia (§22.10 models/, ADR-0001) —
  Malli. State-machine com ciclo enum FIXO em codigo (participacao/logic, fonte unica; os CHECK da mig
  0043 espelham). SEM protocolo/prazo (nao e' obrigacao com relogio — e' um comentario). `autor-identidade-id`
  e' SEMPRE presente (SEM variante anonima, diferente da ouvidoria)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.participacao.logic :as logic]))

(def Comentario
  "Comentario persistido (participacao.comentario)."
  [:map {:closed true}
   [:id :uuid]
   [:ente-id km/EnteId]
   [:proposicao-id :uuid]
   [:autor-identidade-id :uuid]
   [:corpo [:string {:min 1}]]
   [:estado (km/enum-de logic/estados-comentario)]
   [:motivo-rejeicao [:maybe (km/enum-de logic/motivos-rejeicao-comentario)]]
   [:denunciado :boolean]
   [:created-by {:optional true} [:maybe :uuid]]
   [:criado-em km/Instante]
   [:atualizado-em km/Instante]])
