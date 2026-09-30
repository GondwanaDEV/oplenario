(ns oplenario.admin-sistema.logic
  "Nucleo PURO do ciclo de vida da Casa (ADR-0018, fatia 1) — zero I/O. Quem suspende e por que (Eixo 1), o que a
  suspensao faz com a sessao ao vivo (Eixo 2) e quando o incidente sem 2a aprovacao volta atras (Eixo 1b)."
  (:import (java.time Duration Instant)))

(set! *warn-on-reflection* true)

(def motivos-de-suspensao
  "Eixo 1a: a lista FECHADA. \"Outro\" nao entra — forca a nomear o motivo."
  #{"inadimplencia" "pedido_da_casa" "ordem_judicial" "incidente_de_seguranca"})

(def origens-do-encerramento
  "Eixo 4.1: o pedido de encerramento vem da Casa (oficio) ou e' nosso (fim de contrato)."
  #{"pedido_da_casa" "fim_de_contrato"})

(def motivo-do-encerramento
  "A Casa com o encerramento em curso fica `suspenso` com este motivo (Eixo 4.1)."
  "encerramento_em_curso")

(def prazo-da-segunda-aprovacao
  "Eixo 1b: o incidente de seguranca suspende com um operador so' e exige a 2a aprovacao em ate' 24 h."
  (Duration/ofHours 24))

(defn corta-na-hora?
  "Eixo 2 (sessao ao vivo): `ordem_judicial` e `incidente_de_seguranca` cortam na hora; o resto espera o
  encerramento da sessao em curso."
  [motivo]
  (contains? #{"ordem_judicial" "incidente_de_seguranca"} motivo))

(defn um-operador-basta?
  "So' o incidente suspende com um operador (e ainda assim pede a 2a aprovacao em 24 h)."
  [acao motivo]
  (and (= "suspender" acao) (= "incidente_de_seguranca" motivo)))

(defn confirmar-ate
  "O prazo da 2a aprovacao de um pedido, ou nil (so' o incidente tem)."
  ^Instant [acao motivo ^Instant agora]
  (when (um-operador-basta? acao motivo) (.plus agora ^Duration prazo-da-segunda-aprovacao)))

(defn motivo-da-casa
  "O motivo que a Casa passa a ter quando o pedido se efetiva."
  [{:keys [acao motivo]}]
  (if (= "encerrar" acao) motivo-do-encerramento motivo))

(defn efeito-da-aprovacao
  "O que acontece quando o pedido fica aprovado: `:imediato` (a Casa fica suspensa agora), `:agendado` (espera a
  sessao em curso encerrar) ou `:ja-efetivado` (o incidente ja' tinha suspendido; a aprovacao so' confirma)."
  [{:keys [acao motivo efetivado-em]} sessao-em-curso?]
  (cond
    efetivado-em :ja-efetivado
    (and (= "suspender" acao) (corta-na-hora? motivo)) :imediato
    sessao-em-curso? :agendado
    :else :imediato))

(defn incidente-vencido?
  "O pedido de incidente ainda aberto cujo prazo da 2a aprovacao passou (a Casa volta a ativa)."
  [{:keys [estado confirmar-ate]} ^Instant agora]
  (boolean (and (= "aguardando" estado) confirmar-ate (not (.isBefore agora ^Instant confirmar-ate)))))
