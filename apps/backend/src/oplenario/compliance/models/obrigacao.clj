(ns oplenario.compliance.models.obrigacao
  "Representacao INTERNA (dominio) da OBRIGACAO materializada (§22.7.7) — Malli (§22.10 models/). Sabor
  DEADLINE-BOUND: tem `vence_em` (LocalDate — compliance opera em datas) e um ciclo enum FIXO em codigo
  (pendente|cumprida|vencida|dispensada|cancelada). Polimorfica (objeto_tipo/objeto_id, disc.6:
  proposicao_prazo_ativo -> prazo_dominio_ativo). Enums de compliance.logic (fonte unica; os CHECK da
  mig 0005 espelham). `template_chave` cruza o catalogo do motor por guard, nunca FK cross-schema (§22.10)."
  (:require [oplenario.compliance.logic :as logic]
            [oplenario.kernel.malli :as km])
  (:import (java.time LocalDate)))

(def Data
  "Data civil (java.time.LocalDate) — vencimento de prazo de compliance."
  [:fn {:error/message "deve ser java.time.LocalDate"} #(instance? LocalDate %)])

(def Obrigacao
  "Obrigacao deadline-bound persistida (compliance.prazo_dominio_ativo)."
  [:map {:closed true}
   [:id :uuid]
   [:ente-id km/EnteId]
   [:template-chave [:string {:min 1}]]
   [:objeto-tipo [:string {:min 1}]]
   [:objeto-id :uuid]
   [:vence-em Data]
   [:prazo-fonte-ref {:optional true} [:maybe [:string {:min 1}]]]
   [:estado (km/enum-de logic/fases-obrigacao)]
   [:cumprida-em {:optional true} [:maybe km/Instante]]
   [:criado-em km/Instante]
   [:atualizado-em km/Instante]])
