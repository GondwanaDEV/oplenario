(ns oplenario.transparencia.diplomat.consumers
  "Inbound (§22.10 diplomat/consumers, ADR-0001, F6c Slice 1): registra no bus (outbox) os handlers que
  MATERIALIZAM os eventos PUBLICOS de `legislativo` (proposicao.protocolada/transicionou, norma.publicada) nas
  tabelas de read-model de `transparencia` (mig 0044), via `components.repositorio/projetar-evento!` (o db/ de
  um modulo so' e' importado pelo seu Repo-Component, regra do import-lint — arquitetura-test/violacoes-db). O
  relay (sistema.clj) drena o shared.outbox e despacha estes handlers DENTRO da MESMA tx do dedup por
  (consumidor, idempotency-key) (§22.9 E2) — atomico: um rollback (handler lancou, ou o UPDATE final falhou)
  desfaz a projecao junto, entao um redrive nao duplica (o dedup so' commita quando TUDO commita).

  Os TIPOS de evento sao STRINGS LITERAIS, NAO imports de `legislativo.events.*` — §22.10 proibe import
  cross-modulo; o nome do evento e' o CONTRATO DE FIACAO do bus, nao um tipo compartilhado. Mesmo padrao ja
  em producao: tempo_real/canais.clj hardcoda 'votacao.aberta' etc. sem importar legislativo."
  (:require [oplenario.kernel.outbox :as outbox]
            [oplenario.transparencia.components.repositorio :as repo]))

(def ^:private nome-consumidor "transparencia-portal")

(def tipos-consumidos
  "FONTE UNICA dos tipos consumidos pelo projetor do portal (evita drift entre `repo/projetar-evento!` e o
  registro no bus, mesmo racional de tempo_real/consumer.clj)."
  ["proposicao.protocolada" "proposicao.transicionou" "norma.publicada"])

(defn registrar
  "Funde os handlers do projetor do portal num `registro` EXISTENTE (outbox/registrar por tipo) — combinavel
  com o(s) de outro(s) projetor(es) no MESMO relay (ex.: tempo_real). Passado a sistema.clj."
  [registro]
  (reduce (fn [reg tipo] (outbox/registrar reg nome-consumidor tipo repo/projetar-evento!))
          registro tipos-consumidos))
