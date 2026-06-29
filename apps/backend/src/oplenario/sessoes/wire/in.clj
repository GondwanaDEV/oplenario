(ns oplenario.sessoes.wire.in
  "Representacao EXTERNA de ENTRADA da sessao (§22.10 wire/in, ADR-0001) — o contrato do corpo de request, em
  tipos JSON (strings). O `adapters/in` valida contra isto e coage p/ o dominio. `:closed true` recusa campos
  extra (defesa de borda); o autor/tenant NAO vem do corpo (vem do `ator` resolvido na auth)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.sessoes.logic :as logic]))

(def AgendarSessao
  "Corpo de POST /sessoes. `sessao-legislativa-id` = uuid (string); `agendada-para` = ISO-8601 (string).
  capabilities-override fica fora da V1 da borda (o tipo resolve os defaults; override entra quando pedido)."
  [:map {:closed true}
   [:sessao-legislativa-id :string]
   [:tipo-sessao (km/enum-de logic/tipos-sessao)]
   [:modalidade {:optional true} [:maybe (km/enum-de logic/modalidades-sessao)]]
   [:agendada-para {:optional true} [:maybe :string]]])
