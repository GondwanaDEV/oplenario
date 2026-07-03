(ns oplenario.participacao.models.prazo-ativo
  "Representacao INTERNA (dominio) do PRAZO materializado (§22.10 models/, ADR-0001) — Malli. Forma disc.6
  (polimorfico objeto_tipo/objeto_id), adotada DENTRO do schema participacao (decisao Arch B de F6). Ciclo
  enum FIXO em codigo (participacao/logic; os CHECK da mig 0039 espelham). `vence-em` = LocalDate (o prazo
  corre por dia civil); o monitoramento e' read-derivation pura sobre ele (logic/dias-restantes)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.participacao.logic :as logic])
  (:import (java.time LocalDate)))

(def Data
  "Data civil (java.time.LocalDate) — vencimento do prazo."
  [:fn {:error/message "deve ser java.time.LocalDate"} #(instance? LocalDate %)])

(def PrazoAtivo
  "Prazo ativo persistido (participacao.prazo_ativo)."
  [:map {:closed true}
   [:id :uuid]
   [:ente-id km/EnteId]
   [:objeto-tipo (km/enum-de logic/objeto-tipos-prazo)]
   [:objeto-id :uuid]
   [:vence-em Data]
   [:estado (km/enum-de logic/estados-prazo)]
   [:base-dias {:optional true} [:maybe :int]]
   [:prorrogado-ate {:optional true} [:maybe Data]]
   [:prazo-fonte-ref {:optional true} [:maybe [:string {:min 1}]]]
   [:cumprida-em {:optional true} [:maybe km/Instante]]
   [:created-by {:optional true} [:maybe :uuid]]
   [:criado-em km/Instante]
   [:atualizado-em km/Instante]])
