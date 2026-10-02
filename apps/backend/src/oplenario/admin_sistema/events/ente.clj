(ns oplenario.admin-sistema.events.ente
  "Os eventos do ciclo de vida da Casa (ADR-0018, Eixo 3) — contrato Malli validado na fonte. O `ente_id` do envelope
  e' a Casa (o outbox e' drenado cross-tenant pelo relay). Quem consome (hoje: a cota de IA, `integracao_ia`) usa o
  tipo como STRING LITERAL (contrato de fiacao do bus, §22.10). O motivo comercial viaja so' entre modulos do core:
  nenhum consumidor o publica."
  (:require [oplenario.kernel.eventos :as eventos]))

(def suspensa-tipo "admin_sistema.casa.suspensa")
(def reativada-tipo "admin_sistema.casa.reativada")

(def SuspensaPayload
  [:map {:closed true}
   [:motivo [:enum "inadimplencia" "pedido_da_casa" "ordem_judicial" "incidente_de_seguranca" "encerramento_em_curso"]]
   [:desde :string]
   [:pedido-id :uuid]])

(def ReativadaPayload
  [:map {:closed true}
   [:em :string]
   [:motivo-anterior [:maybe :string]]
   [:por [:enum "operador" "incidente_sem_segunda_aprovacao" "suspensao_recusada"]]])

(defn suspensa [ente-id payload] (eventos/evento-validado SuspensaPayload suspensa-tipo ente-id payload))
(defn reativada [ente-id payload] (eventos/evento-validado ReativadaPayload reativada-tipo ente-id payload))
