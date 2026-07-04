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
   "proposicao.protocolada" "proposicao.transicionou"])

(defn registrar
  "Funde os handlers dos projetores de `paineis` num `registro` EXISTENTE (outbox/registrar por tipo) —
  combinavel com o(s) de outro(s) projetor(es) no MESMO relay (ex.: transparencia, tempo_real)."
  [registro]
  (reduce (fn [reg tipo] (outbox/registrar reg nome-consumidor tipo repo/projetar-evento!))
          registro tipos-consumidos))
