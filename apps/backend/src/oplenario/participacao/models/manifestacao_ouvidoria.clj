(ns oplenario.participacao.models.manifestacao-ouvidoria
  "Representacao INTERNA (dominio) da MANIFESTACAO de ouvidoria (§22.10 models/, ADR-0001) — Malli.
  State-machine com ciclo enum FIXO em codigo (participacao/logic, fonte unica; os CHECK da mig 0042
  espelham). `recibo-em` e' o marco de inicio do relogio (Instante). ANONIMA nao e' sem-auth:
  `manifestante-identidade-id` e' `[:maybe :uuid]` — nil SOMENTE quando `anonima` true (o CHECK
  manifestacao_anonima_coerente da mig 0042 espelha no banco)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.participacao.logic :as logic]))

(def ManifestacaoOuvidoria
  "Manifestacao de ouvidoria persistida (participacao.manifestacao_ouvidoria)."
  [:map {:closed true}
   [:id :uuid]
   [:ente-id km/EnteId]
   [:ano :int]
   [:sequencial :int]
   [:protocolo [:string {:min 1}]]
   [:tipo (km/enum-de logic/tipos-manifestacao)]
   [:assunto [:string {:min 1}]]
   [:descricao [:string {:min 1}]]
   [:anonima :boolean]
   [:manifestante-identidade-id [:maybe :uuid]]
   [:estado (km/enum-de logic/estados-manifestacao)]
   [:recibo-em km/Instante]
   [:created-by {:optional true} [:maybe :uuid]]
   [:criado-em km/Instante]
   [:atualizado-em km/Instante]])
