(ns oplenario.compliance.models.avaliacao
  "Representacao INTERNA (dominio) da AVALIACAO de compliance (§22.7.7) — Malli (§22.10 models/). A prova
  de compliance APPEND-ONLY (compliance.compliance_avaliacao, Invariante 10): veredito + origem + a versao
  do registry carimbada (B3). `obrigacao_id` e' NULL p/ regra continua / veredito 'inaplicavel'. Enums de
  compliance.logic (fonte unica; os CHECK da mig 0005 espelham)."
  (:require [oplenario.compliance.logic :as logic]
            [oplenario.kernel.malli :as km]))

(def Avaliacao
  "Avaliacao auditada (compliance.compliance_avaliacao). `obrigacao-id` NULL p/ continua/inaplicavel."
  [:map {:closed true}
   [:id :uuid]
   [:ente-id km/EnteId]
   [:obrigacao-id {:optional true} [:maybe :uuid]]
   [:template-chave [:string {:min 1}]]
   [:registry-versao-ref [:string {:min 1}]]
   [:veredito (km/enum-de logic/vereditos)]
   [:severidade (km/enum-de logic/severidades)]
   [:origem-avaliacao (km/enum-de logic/origens-avaliacao)]
   [:detalhe {:optional true} [:maybe [:string {:min 1}]]]
   [:avaliado-em km/Instante]])
