(ns oplenario.compliance.runtime-test
  "INTEGRACAO (PG real) — F5.1: o runtime do motor de compliance SAI DO ATOM. O compliance OPERA o seam
  `motor/avaliar` (F2) e PERSISTE o ciclo nas SUAS tabelas (schema compliance, §22.7.7): a obrigacao
  materializada (`prazo_dominio_ativo`) e a prova de compliance APPEND-ONLY (`compliance_avaliacao`,
  Invariante 10). Prova end-to-end (mesma tx do tenant):
    - materializa obrigacao DEADLINE-BOUND (idempotente na chave ente⋈template⋈objeto);
    - reconcilia o ciclo (pendente -> cumprida quando vira conforme; pendente -> vencida apos o prazo);
    - audita CADA avaliacao (append-only), incl. inaplicavel (obrigacao_id NULL).
  A regra usa parametro_tenant (binding toggle, db-backed) p/ o veredito + prazo_vigente (db-backed) p/ o
  deadline — sem depender de fato de dominio nem do conteudo regulatorio real ([GAP] segue GAP)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [honey.sql]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [oplenario.compliance.components.repositorio :as repo-compliance]
            [oplenario.compliance.db.avaliacao :as db-aval]
            [oplenario.compliance.db.obrigacao :as db-obr]
            [oplenario.compliance.models.avaliacao :as mod-aval]
            [oplenario.compliance.models.obrigacao :as mod-obr]
            [oplenario.config :as config]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.repositorio :as rm]
            [oplenario.motor.nucleo :as nuc]
            [oplenario.sistema :as sistema])
  (:import (java.time LocalDate)))

(def ^:dynamic *sys* nil)

(use-fixtures :once
  (fn [t]
    (let [s (component/start (sistema/novo-sistema (config/carregar)))]
      (migracao/migrar! (:ds (:datasource s)))
      (binding [*sys* s] (try (t) (finally (component/stop s)))))))

;; regra de prova: veredito por binding (parametro_tenant), deadline por prazo_vigente (ambos db-backed).
(def ^:private TPL "
template: prova_runtime_f5
contexto: compliance
dominio: tribunal_de_contas
parametros: { competencia: Competencia }
aplica_quando: verdadeiro
exige: parametro_tenant(\"remetido\")
prazo:
  janela: prazo_vigente(\"TCE-CE\", \"SIM_mensal\", competencia)
  a_partir_de: fim_de(competencia)
severidade: bloqueante
referencia_normativa: \"prova F5.1\"
")

(def ^:private TPL-INAPLICAVEL "
template: prova_inaplicavel_f5
contexto: compliance
dominio: tribunal_de_contas
parametros: { competencia: Competencia }
aplica_quando: falso
exige: parametro_tenant(\"remetido\")
prazo:
  janela: prazo_vigente(\"TCE-CE\", \"SIM_mensal\", competencia)
  a_partir_de: fim_de(competencia)
severidade: aviso
referencia_normativa: \"prova F5.1 (inaplicavel)\"
")

(def ^:private reg-ver "registry-v1@2026-06-20")

(defn- seed-prazo! [ds repo-motor chave-periodo data-limite]
  ;; isolamento de teste: o prazo de dominio tem UNIQUE parcial por (dominio,chave_dominio,tipo,periodo)
  ;; vigente — limpa a linha de execucoes anteriores antes de semear (espelha avaliar_seam_test).
  (jdbc/execute-one! ds ["DELETE FROM motor.prazo_dominio_vigente WHERE chave_periodo = ?" chave-periodo])
  (rm/criar-prazo! repo-motor {:id (random-uuid) :dominio "tribunal_de_contas" :chave-dominio "TCE-CE"
                               :tipo-prazo "SIM_mensal" :chave-periodo chave-periodo
                               :data-limite data-limite :fonte "IN 04/2019" :vigente true}))

(defn- seed-binding! [repo-motor ente template-chave remetido?]
  (rm/criar-binding! repo-motor ente {:id (random-uuid) :ente-id ente :template-chave template-chave
                                      :ativa true :parametros-tenant {"remetido" remetido?}}))

(defn- avaliar! [repo-compliance ente {:keys [regra objeto-id objeto-tipo amb agora origem]}]
  (let [{:keys [registro-fatos repo-motor]} *sys*]
    (repo-compliance/avaliar-obrigacao! repo-compliance ente registro-fatos repo-motor
      {:regra (nuc/carregar-envelope regra) :reg-ver reg-ver
       :objeto-tipo (or objeto-tipo "competencia") :objeto-id objeto-id
       :amb amb :agora agora :origem (or origem "evento")})))

;; ---------- materializa pendente (nao-conforme, dentro do prazo) ----------

(deftest materializa-obrigacao-pendente
  (let [{:keys [datasource repo-motor repo-compliance]} *sys*
        ds (:ds datasource) ente (random-uuid) objeto (random-uuid)]
    (seed-prazo! ds repo-motor "2099-12" (LocalDate/of 2099 12 31))
    (seed-binding! repo-motor ente "prova_runtime_f5" false)        ; ainda nao remeteu
    (let [r (avaliar! repo-compliance ente {:regra TPL :objeto-id objeto
                                            :amb {"competencia" {:ano 2099 :mes 12}}
                                            :agora (LocalDate/of 2026 6 19)})
          obr (:obrigacao r)]
      (is (= "pendente" (:estado obr)) "nao-conforme dentro do prazo materializa PENDENTE")
      (is (= (LocalDate/of 2099 12 31) (:vence-em obr)) "vence_em lido de motor.prazo_dominio_vigente")
      (is (= "IN 04/2019" (:prazo-fonte-ref obr)) "prazo_fonte_ref capturado da fonte do prazo de dominio")
      (is (m/validate mod-obr/Obrigacao obr) "bate o model de obrigacao")
      (is (= "nao_conforme" (:veredito (:avaliacao r))) "veredito nao_conforme (remetido=false)")
      ;; a obrigacao foi persistida e e' legivel
      (tenancy/com-tenant* ds ente
        (fn [tx]
          (let [persist (db-obr/buscar tx ente (:id obr))]
            (is (= "pendente" (:estado persist)) "obrigacao persistida no banco")
            (is (m/validate mod-obr/Obrigacao persist) "model bate na leitura do banco")
            (let [avals (db-aval/listar-da-obrigacao tx ente (:id obr))]
              (is (= 1 (count avals)) "uma avaliacao auditada (append-only)")
              (is (m/validate mod-aval/Avaliacao (first avals)) "bate o model de avaliacao")
              (is (= (:id obr) (:obrigacao-id (first avals))) "avaliacao referencia a obrigacao"))))))))

;; ---------- cumpre quando vira conforme (re-avaliacao do MESMO objeto = idempotente na materializacao) ----------

(deftest cumpre-quando-conforme
  (let [{:keys [datasource repo-motor repo-compliance]} *sys*
        ds (:ds datasource) ente (random-uuid) objeto (random-uuid)]
    (seed-prazo! ds repo-motor "2099-11" (LocalDate/of 2099 11 30))
    (seed-binding! repo-motor ente "prova_runtime_f5" false)
    (let [r1  (avaliar! repo-compliance ente {:regra TPL :objeto-id objeto
                                              :amb {"competencia" {:ano 2099 :mes 11}}
                                              :agora (LocalDate/of 2026 6 19)})
          oid (:id (:obrigacao r1))]
      (is (= "pendente" (:estado (:obrigacao r1))) "1a avaliacao: pendente")
      ;; o ente remete: binding vira true
      (seed-binding! repo-motor ente "prova_runtime_f5" true)
      (let [r2 (avaliar! repo-compliance ente {:regra TPL :objeto-id objeto
                                               :amb {"competencia" {:ano 2099 :mes 11}}
                                               :agora (LocalDate/of 2026 6 20) :origem "sob_demanda"})]
        (is (= oid (:id (:obrigacao r2))) "MESMA obrigacao (idempotencia da chave ente⋈template⋈objeto)")
        (is (= "cumprida" (:estado (:obrigacao r2))) "virou conforme -> CUMPRIDA")
        (is (some? (:cumprida-em (:obrigacao r2))) "cumprida_em carimbada")
        (is (= "conforme" (:veredito (:avaliacao r2))) "veredito conforme")
        (tenancy/com-tenant* ds ente
          (fn [tx]
            (is (= 1 (count (db-obr/listar-do-objeto tx ente "competencia" objeto))) "UMA linha de obrigacao (nao duplicou)")
            (is (= 2 (count (db-aval/listar-da-obrigacao tx ente oid))) "DUAS avaliacoes (append-only acumula a prova)")))))))

;; ---------- vence quando passa do prazo (nao-conforme + agora apos vence_em) ----------

(deftest vence-quando-passa-do-prazo
  (let [{:keys [datasource repo-motor repo-compliance]} *sys*
        ds (:ds datasource) ente (random-uuid) objeto (random-uuid)]
    (seed-prazo! ds repo-motor "2020-01" (LocalDate/of 2020 1 31))     ; prazo no passado
    (seed-binding! repo-motor ente "prova_runtime_f5" false)
    (let [r (avaliar! repo-compliance ente {:regra TPL :objeto-id objeto
                                            :amb {"competencia" {:ano 2020 :mes 1}}
                                            :agora (LocalDate/of 2026 6 19) :origem "sweep"})]
      (is (= "vencida" (:estado (:obrigacao r))) "nao-conforme apos o prazo = VENCIDA")
      (is (= "nao_conforme" (:veredito (:avaliacao r))) "veredito nao_conforme"))))

;; ---------- inaplicavel: NAO materializa obrigacao, so audita (obrigacao_id NULL) ----------

(deftest inaplicavel-nao-materializa
  (let [{:keys [datasource repo-motor repo-compliance]} *sys*
        ds (:ds datasource) ente (random-uuid) objeto (random-uuid)]
    (seed-prazo! ds repo-motor "2099-10" (LocalDate/of 2099 10 31))
    (seed-binding! repo-motor ente "prova_inaplicavel_f5" false)
    (let [r (avaliar! repo-compliance ente {:regra TPL-INAPLICAVEL :objeto-id objeto
                                            :amb {"competencia" {:ano 2099 :mes 10}}
                                            :agora (LocalDate/of 2026 6 19)})]
      (is (nil? (:obrigacao r)) "aplica_quando=falso -> NAO materializa obrigacao")
      (is (= "inaplicavel" (:veredito (:avaliacao r))) "veredito inaplicavel")
      (tenancy/com-tenant* ds ente
        (fn [tx]
          (is (empty? (db-obr/listar-do-objeto tx ente "competencia" objeto)) "nenhuma obrigacao materializada")
          (let [a (db-aval/ultima-do-template tx ente "prova_inaplicavel_f5")]
            (is (= "inaplicavel" (:veredito a)) "a avaliacao inaplicavel foi auditada")
            (is (nil? (:obrigacao-id a)) "obrigacao_id NULL para inaplicavel")))))))

;; ---------- compliance_avaliacao e' APPEND-ONLY (Invariante 10) ----------

(deftest avaliacao-append-only
  (let [{:keys [datasource repo-motor repo-compliance]} *sys*
        ds (:ds datasource) ente (random-uuid) objeto (random-uuid)]
    (seed-prazo! ds repo-motor "2099-09" (LocalDate/of 2099 9 30))
    (seed-binding! repo-motor ente "prova_runtime_f5" false)
    (let [r (avaliar! repo-compliance ente {:regra TPL :objeto-id objeto
                                            :amb {"competencia" {:ano 2099 :mes 9}}
                                            :agora (LocalDate/of 2026 6 19)})
          aid (:id (:avaliacao r))]
      (tenancy/com-tenant* ds ente
        (fn [tx]
          (is (thrown? Exception
                (jdbc/execute-one! tx
                  (honey.sql/format {:update :compliance.compliance_avaliacao
                                     :set {:veredito "conforme"}
                                     :where [:and [:= :ente_id ente] [:= :id aid]]})))
              "UPDATE numa avaliacao e' barrado (append-only: a prova de compliance e' imutavel)"))))))
