(ns oplenario.paineis.diplomat.notificador
  "Outbound (ADR-0001 diplomat/): impl da porta CanalNotificacao (F7 E2). NotificadorLog e' a impl STUB desta
  fatia — REGISTRA a entrega (nao envia de fato) e devolve {:ok? true}. A entrega REAL (SMTP/push) + a
  resolucao identidade-UUID->contato (e-mail/token de push) sao CARRY infra (fundacao #3): precisam de um
  servidor de e-mail em-regiao + um port de identidade (§22.10 comm so' HTTP/eventos) — indisponiveis. O
  ledger (paineis.notificacao_entrega) ja' registra o intent 'pendente'; quando a infra chegar, uma impl
  NotificadorSMTP substitui esta no Component (sistema.clj via `using`) sem tocar o worker de entrega."
  (:require [clojure.tools.logging :as log]
            [oplenario.paineis.components.notificacao :as porta]))

(defrecord NotificadorLog []
  porta/CanalNotificacao
  (enviar! [_ {:keys [canal destinatario assunto]}]
    (log/info "notificador (STUB): entrega registrada — envio real e' carry infra"
              {:canal canal :destinatario destinatario :assunto assunto})
    {:ok? true}))

(defn notificador-log
  "Cria a impl STUB da porta (stateless). Substituivel por NotificadorSMTP quando a infra de e-mail existir."
  []
  (->NotificadorLog))
