(ns obrigacoes-legais
  "Semente NARRATIVA da demo — as OBRIGACOES LEGAIS da Casa no painel de compliance (ADR-0021 fatia 3), a 7a etapa,
  depois de `audiencias` e `contas` (mesmo contrato das outras: `semear!` recebe um `sistema` Component JA' BOOTADO e
  a Casa `ente`).

  A DISCIPLINA, a mesma de `compliance`: nao se escreve estado de obrigacao na mao. Esta semente so' chama o GATILHO de
  producao (`oplenario.gatilho-compliance/disparar!`) — o que o painel faria na primeira leitura —, e o placar sai do
  motor contra os fatos que as sementes anteriores deixaram:
    - metas fiscais 2026-Q1 CUMPRIDA (a audiencia de 27/05 encerrada com ata, de `audiencias`);
    - metas fiscais 2026-Q2 VENCIDA em 30/09 (nao houve audiencia) — e 2025-Q3, se ainda na janela de 365 dias;
    - julgamento das contas de 2024 PENDENTE, com o prazo congelado no registro; as de 2023 CUMPRIDA (julgadas).

  IDEMPOTENCIA: e' a do gatilho (template e vinculo criados so' se faltam; obrigacao pela chave Casa⋈regra⋈objeto)."
  (:require [oplenario.gatilho-compliance :as gatilho]
            [oplenario.kernel.tempo :as tempo])
  (:import (java.time LocalDate ZoneId)))

(set! *warn-on-reflection* true)

(def ^:private fuso (ZoneId/of "America/Fortaleza"))

(defn deps
  "As deps do gatilho sobre o sistema bootado (as mesmas que o host monta em `rotas.clj`)."
  [sistema hoje]
  {:repo-compliance (:repo-compliance sistema) :repo-motor (:repo-motor sistema)
   :registro-fatos (:registro-fatos sistema) :repo-legislativo (:repo-legislativo sistema)
   :hoje (constantly hoje)})

(defn semear!
  "Roda o gatilho para a Casa `ente`. `hoje` injetavel (o teste crava a data). Devolve o resumo do gatilho."
  ([sistema ente] (semear! sistema ente (tempo/hoje (tempo/relogio-sistema) fuso)))
  ([sistema ente ^LocalDate hoje]
   (gatilho/disparar! (deps sistema hoje) ente {:origem "evento"})))
