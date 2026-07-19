(ns oplenario.paineis.diplomat.consumers
  "Inbound (§22.10 diplomat/consumers, ADR-0001, F7): registra no bus (outbox) os handlers que MATERIALIZAM
  os eventos consumidos pelo modulo `paineis` — F7 Slice 1: protocolo/fechamento/vencimento/prorrogacao dos
  4 relogios de `participacao` (e-SIC/recurso/LGPD/ouvidoria); F7 Slice 2: protocolo/transicao de
  `legislativo` (board de tramitacao) — nas tabelas de read-model de `paineis` (migs 0048/0049), via
  `components.repositorio/projetar-evento!`. Mesmo mecanismo de transparencia/diplomat/consumers — o relay
  drena o shared.outbox e despacha este handler DENTRO da MESMA tx do dedup (§22.9 E2). UM SO consumidor p/
  o modulo INTEIRO (nao um por read-model) — mesma convencao de `transparencia-portal`, que tambem cobre
  varios read-models (materia/norma/artefato) sob um nome so'."
  (:require [oplenario.kernel.outbox :as outbox]
            [oplenario.paineis.components.repositorio :as repo]))

(def ^:private nome-consumidor "paineis")

(def tipos-consumidos
  "FONTE UNICA dos tipos consumidos pelos projetores de `paineis` (evita drift entre `repo/projetar-evento!`
  e o registro no bus, mesmo racional de transparencia/diplomat/consumers)."
  ["participacao.pedido_esic.protocolado" "participacao.recurso_esic.protocolado"
   "participacao.solicitacao_titular.protocolada" "participacao.manifestacao_ouvidoria.protocolada"
   "participacao.pedido_esic.respondido" "participacao.recurso_esic.decidido"
   "participacao.solicitacao_titular.respondida" "participacao.manifestacao_ouvidoria.respondida"
   "participacao.manifestacao_ouvidoria.arquivada"
   "participacao.prazo.vencido" "participacao.prazo.prorrogado"
   "proposicao.protocolada" "proposicao.transicionou"
   ;; F7 E2: o fan-out de transparencia (`notificacao.requisitada`, 1 por seguidor) -> intent no ledger de entrega.
   "notificacao.requisitada"
   ;; F7 E3: o ciclo de vida da SESSAO plenaria (F4) -> vista de SLI de janela de sessao (Inv.9). `agendada`
   ;; materializa a linha no nascimento (fecha a cegueira ao no-show); `transicionou` move a janela/estado.
   "sessao.agendada" "sessao.transicionou"])

;; Onda E fatia 1: SEGUNDO consumidor do modulo — a INBOX interna. Nome DISTINTO = dedup independente por
;; (consumidor, key), exatamente como `transparencia-portal` x `transparencia-notificacao`. Consome o MESMO
;; `notificacao.requisitada`, mas so' age no canal `in_app` (ver repo/projetar-inbox!).
(def ^:private nome-consumidor-inbox "paineis-inbox")
(def ^:private tipos-inbox ["notificacao.requisitada"])

(defn registrar
  "Funde os handlers dos projetores de `paineis` num `registro` EXISTENTE (outbox/registrar por tipo) —
  combinavel com o(s) de outro(s) projetor(es) no MESMO relay. Registra DOIS consumidores: o projetor
  geral (`paineis`, todos os tipos, incl. o ledger de entrega de e-mail) e a INBOX (`paineis-inbox`, so'
  `notificacao.requisitada` de canal in_app) — nomes distintos = dedup independente."
  [registro]
  (as-> registro reg
    (reduce (fn [r tipo] (outbox/registrar r nome-consumidor tipo repo/projetar-evento!)) reg tipos-consumidos)
    (reduce (fn [r tipo] (outbox/registrar r nome-consumidor-inbox tipo repo/projetar-inbox!)) reg tipos-inbox)))
