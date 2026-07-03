(ns oplenario.participacao.models.recurso-esic
  "Representacao INTERNA (dominio) do RECURSO e-SIC (§22.10 models/, ADR-0001) — Malli. State-machine com
  ciclo enum FIXO em codigo (participacao/logic; os CHECK da mig 0040 espelham). ENTIDADE SEPARADA do pedido
  (relogio proprio): `recibo-em` = marco de inicio do relogio do recurso; `pedido-id` = ref intra-schema ao
  pedido recorrido (uuid, FK composta no banco). `decidido-em` so no desfecho terminal."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.participacao.logic :as logic]))

(def RecursoEsic
  "Recurso e-SIC persistido (participacao.recurso_esic)."
  [:map {:closed true}
   [:id :uuid]
   [:ente-id km/EnteId]
   [:pedido-id :uuid]
   [:ano :int]
   [:sequencial :int]
   [:protocolo [:string {:min 1}]]
   [:instancia :int]
   [:motivo [:string {:min 1}]]
   [:estado (km/enum-de logic/estados-recurso)]
   [:recibo-em km/Instante]
   [:decidido-em {:optional true} [:maybe km/Instante]]
   [:created-by {:optional true} [:maybe :uuid]]
   [:criado-em km/Instante]
   [:atualizado-em km/Instante]])
