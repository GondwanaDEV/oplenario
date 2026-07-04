(ns oplenario.paineis.diplomat.consumers
  "Inbound (§22.10 diplomat/consumers, ADR-0001, F7 Slice 1): registra no bus (outbox) os handlers que
  MATERIALIZAM os eventos de `participacao` (protocolo/fechamento/vencimento/prorrogacao dos 4 relogios
  e-SIC/recurso/LGPD/ouvidoria) nas tabelas de read-model de `paineis` (mig 0048), via
  `components.repositorio/projetar-evento!`. Mesmo mecanismo de transparencia/diplomat/consumers — o relay
  drena o shared.outbox e despacha este handler DENTRO da MESMA tx do dedup (§22.9 E2)."
  (:require [oplenario.kernel.outbox :as outbox]
            [oplenario.paineis.components.repositorio :as repo]))

(def ^:private nome-consumidor "paineis-pendencias")

(def tipos-consumidos
  "FONTE UNICA dos tipos consumidos pelo projetor de pendencias (evita drift entre `repo/projetar-evento!` e
  o registro no bus, mesmo racional de transparencia/diplomat/consumers)."
  ["participacao.pedido_esic.protocolado" "participacao.recurso_esic.protocolado"
   "participacao.solicitacao_titular.protocolada" "participacao.manifestacao_ouvidoria.protocolada"
   "participacao.pedido_esic.respondido" "participacao.recurso_esic.decidido"
   "participacao.solicitacao_titular.respondida" "participacao.manifestacao_ouvidoria.respondida"
   "participacao.manifestacao_ouvidoria.arquivada"
   "participacao.prazo.vencido" "participacao.prazo.prorrogado"])

(defn registrar
  "Funde os handlers do projetor de pendencias num `registro` EXISTENTE (outbox/registrar por tipo) —
  combinavel com o(s) de outro(s) projetor(es) no MESMO relay (ex.: transparencia, tempo_real)."
  [registro]
  (reduce (fn [reg tipo] (outbox/registrar reg nome-consumidor tipo repo/projetar-evento!))
          registro tipos-consumidos))
