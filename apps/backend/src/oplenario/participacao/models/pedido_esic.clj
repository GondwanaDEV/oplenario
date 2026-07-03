(ns oplenario.participacao.models.pedido-esic
  "Representacao INTERNA (dominio) do PEDIDO e-SIC (§22.10 models/, ADR-0001) — Malli. State-machine com
  ciclo enum FIXO em codigo (participacao/logic, fonte unica; os CHECK da mig 0039 espelham). `recibo-em`
  e' o marco de inicio do relogio (Instant). `solicitante-identidade-id` = forward-ref (uuid, sem FK, §22.10)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.participacao.logic :as logic]))

(def PedidoEsic
  "Pedido e-SIC persistido (participacao.pedido_esic)."
  [:map {:closed true}
   [:id :uuid]
   [:ente-id km/EnteId]
   [:ano :int]
   [:sequencial :int]
   [:protocolo [:string {:min 1}]]
   [:assunto [:string {:min 1}]]
   [:descricao [:string {:min 1}]]
   [:solicitante-identidade-id :uuid]
   [:estado (km/enum-de logic/estados-pedido)]
   [:recibo-em km/Instante]
   [:created-by {:optional true} [:maybe :uuid]]
   [:criado-em km/Instante]
   [:atualizado-em km/Instante]])
