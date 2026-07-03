(ns oplenario.participacao.models.solicitacao-titular
  "Representacao INTERNA (dominio) da SOLICITACAO do titular LGPD (§22.10 models/, ADR-0001) — Malli.
  State-machine com ciclo enum FIXO em codigo (participacao/logic, fonte unica; os CHECK da mig 0041 espelham).
  `tipo` = um dos 5 direitos do titular (art. 18); `detalhe` OPCIONAL (texto livre do titular). `recibo-em` =
  marco de inicio do relogio LGPD (CONTADOR SEPARADO do e-SIC). `titular-identidade-id` = forward-ref (uuid, sem FK)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.participacao.logic :as logic]))

(def SolicitacaoTitular
  "Solicitacao do titular persistida (participacao.solicitacao_titular)."
  [:map {:closed true}
   [:id :uuid]
   [:ente-id km/EnteId]
   [:ano :int]
   [:sequencial :int]
   [:protocolo [:string {:min 1}]]
   [:tipo (km/enum-de logic/tipos-solicitacao-titular)]
   [:titular-identidade-id :uuid]
   [:detalhe {:optional true} [:maybe [:string {:min 1}]]]
   [:estado (km/enum-de logic/estados-solicitacao-titular)]
   [:recibo-em km/Instante]
   [:created-by {:optional true} [:maybe :uuid]]
   [:criado-em km/Instante]
   [:atualizado-em km/Instante]])
