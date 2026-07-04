(ns oplenario.paineis.components.notificacao
  "PORTA de canal de notificacao (F7 E2) — a fronteira do efeito EXTERNO de entrega (e-mail/push). O worker de
  entrega (Repo-Component/entregar-pendentes!) chama `enviar!` sobre um intent JA' commitado no ledger; a impl
  concreta (diplomat/notificador) faz o I/O. Protocolo puro aqui (§22.10 port): a impl real de SMTP/push +
  a resolucao identidade-UUID->contato sao CARRY infra — a impl desta fatia (NotificadorLog) so' registra.")

(defprotocol CanalNotificacao
  (enviar! [this msg]
    "Entrega `msg` = {:canal :destinatario :assunto :corpo :objeto-tipo :objeto-id} pelo canal. Devolve
    {:ok? true} em sucesso, ou {:ok? false :motivo <str>} em falha (o worker persiste enviada/falha a partir
    disto). NAO lanca — uma falha de entrega e' um RESULTADO ({:ok? false}), nao uma excecao (o worker
    processa muitos intents em sequencia; um throw abortaria a rodada inteira)."))
