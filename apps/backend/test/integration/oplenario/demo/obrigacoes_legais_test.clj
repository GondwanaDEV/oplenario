(ns oplenario.demo.obrigacoes-legais-test
  "INTEGRACAO (PG real): `obrigacoes-legais/semear!` — o gatilho de producao sobre a Casa da demo (ADR-0021 fatia 3).
  Depois de `casa`, `contas` e `audiencias`, o painel mostra: metas fiscais 2026-Q1 CUMPRIDA (a audiencia encerrada com
  ata), 2026-Q2 VENCIDA (30/09/2026), e o julgamento das contas de 2024 PENDENTE com o prazo congelado (as de 2023,
  julgadas, cumpridas). Rodar de novo nao duplica.

  A Casa da demo e' FIXA e o banco e' compartilhado com `demo/compliance_test` (que conta o placar inteiro da Casa): este
  teste apaga, ao sair, as obrigacoes e as avaliacoes das DUAS regras dele — e so' delas."
  (:require [audiencias]
            [casa]
            [clojure.test :refer [deftest is testing]]
            [contas :as contas-demo]
            [next.jdbc :as jdbc]
            [obrigacoes-legais]
            [oplenario.compliance.components.repositorio :as repo-compliance]
            [oplenario.demo.casa-test :refer [with-sistema]]
            [oplenario.gatilho-compliance :as gatilho]
            [oplenario.legislativo.components.repositorio-contas :as repo-contas])
  (:import (java.time LocalDate)))

(def ^:private hoje (LocalDate/of 2026 10 3))

(defn- limpar!
  "Apaga o que este teste materializou (como dono do banco, no GUC da Casa — as tabelas sao FORCE RLS)."
  [ds ente]
  (jdbc/with-transaction [tx ds]
    (jdbc/execute! tx ["select set_config('app.ente_id', ?, true)" (str ente)])
    (doseq [t ["compliance.compliance_avaliacao" "compliance.prazo_dominio_ativo"]]
      (jdbc/execute! tx [(str "delete from " t " where ente_id = ? and template_chave in (?, ?)")
                         ente gatilho/chave-metas-fiscais gatilho/chave-contas]))))

(defn- obrigacao [s ente template objeto-tipo objeto-id]
  (->> (repo-compliance/obrigacoes-do-objeto (:repo-compliance s) ente objeto-tipo objeto-id)
       (filter #(= template (:template-chave %)))
       first))

(deftest o-painel-da-demo-mostra-as-obrigacoes-legais
  (with-sistema [s]
    (let [ds (:ds (:datasource s))
          {:keys [ente identidades]} (casa/semear! s)]
      (try
        (let [contas-r (contas-demo/semear! s ente identidades)
              _ (audiencias/semear! s ente identidades)
              r1 (obrigacoes-legais/semear! s ente hoje)
              metas #(obrigacao s ente gatilho/chave-metas-fiscais "competencia"
                                (gatilho/objeto-da-competencia ente {:ano 2026 :mes %}))
              contas #(obrigacao s ente gatilho/chave-contas "prestacao_contas" (get contas-r %))]
          (testing "metas fiscais: o 1o quadrimestre cumprido pela audiencia da demo; o 2o vencido em 30/09"
            (is (= "cumprida" (:estado (metas 4))))
            (is (= (LocalDate/of 2026 5 31) (:vence-em (metas 4))))
            (is (= "vencida" (:estado (metas 8))))
            (is (= (LocalDate/of 2026 9 30) (:vence-em (metas 8)))))
          (testing "contas: 2024 pendente com o prazo congelado da prestacao; 2023 (julgada) cumprida"
            (let [p24 (repo-contas/prestacao (:repo-legislativo s) ente (:governo-2024 contas-r))]
              (is (= "pendente" (:estado (contas :governo-2024))))
              (is (= (:prazo-julgamento-ate p24) (:vence-em (contas :governo-2024)))))
            (is (= "cumprida" (:estado (contas :governo-2023))))
            (is (nil? (contas :mesa-2024)) "as contas da Mesa nao tem obrigacao de julgamento"))
          (testing "idempotente: rodar de novo nao materializa nada novo"
            (let [contar #(count (:em-aberto (repo-compliance/painel (:repo-compliance s) ente {:limite-em-aberto 500})))
                  antes (contar)
                  r2 (obrigacoes-legais/semear! s ente hoje)]
              (is (= antes (contar)))
              (is (= 3 (get-in r1 [:metas-fiscais :avaliadas])) "2025-Q3, 2026-Q1 e 2026-Q2")
              (is (= 2 (get-in r2 [:metas-fiscais :avaliadas]))
                  "as abertas (2025-Q3 e 2026-Q2) sao reavaliadas; a cumprida nao")
              (is (zero? (:vencidas r2))))))
        (finally (limpar! ds ente))))))
