(ns oplenario.participacao.wire.in.solicitacao-titular
  "Representacao EXTERNA de ENTRADA da SOLICITACAO do titular LGPD (§22.10 wire/in, ADR-0001) — o que o TITULAR
  envia no corpo de POST /portal/lgpd/solicitacoes. So os campos que o titular controla: `tipo` (um dos 5
  direitos) + `detalhe` OPCIONAL (texto livre — ex.: qual dado corrigir). O titular e o recibo sao INJETADOS do
  ator/relogio na borda, NUNCA vem do corpo (anti-forge). Map CLOSED — chave a mais e' rejeitada (fail-closed 400)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.participacao.logic :as logic]))

(def SolicitarTitular
  "Corpo de criacao de solicitacao do titular. Closed: allowlist estrita (tipo + detalhe?). `tipo` restrito ao
  enum dos 5 direitos (o adapter coage/valida). `detalhe` opcional; teto (:max 5000) espelha a CHECK da mig 0041
  (defesa-em-profundidade anti-abuso). titular/estado NAO entram — injetados/derivados na borda."
  [:map {:closed true}
   [:tipo (km/enum-de logic/tipos-solicitacao-titular)]
   [:detalhe {:optional true} [:string {:min 1 :max 5000}]]])
