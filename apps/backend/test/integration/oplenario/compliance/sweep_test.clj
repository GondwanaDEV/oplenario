(ns oplenario.compliance.sweep-test
  "INTEGRACAO (PG real) — F5.2: o SWEEP de vencimento (§22.7.7 S1: 'o motor MONITORA prazo, nao so avalia').
  O vencimento (pendente->vencida) e' a UNICA transicao do ciclo que NENHUM evento dispara — e' dirigida
  por TEMPO. O sweep e' PURO por DATA sobre as obrigacoes abertas (`abertas-ate hoje`): nao re-roda o
  motor (a obrigacao segue nao_conforme enquanto nao foi cumprida; o cumprimento vem por evento). Audita
  cada transicao (origem='sweep'), carregando a severidade da regra da ultima avaliacao da obrigacao."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.compliance.db.avaliacao :as db-aval]
            [oplenario.compliance.db.obrigacao :as db-obr]
            [oplenario.compliance.components.repositorio :as repo-compliance]
            [oplenario.config :as config]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.repositorio :as rm]
            [oplenario.motor.nucleo :as nuc]
            [next.jdbc :as jdbc]
            [oplenario.sistema :as sistema])
  (:import (java.time LocalDate)))

(def ^:dynamic *sys* nil)

(use-fixtures :once
  (fn [t]
    (let [s (component/start (sistema/novo-sistema (config/carregar)))]
      (migracao/migrar! (:ds (:datasource s)))
      (binding [*sys* s] (try (t) (finally (component/stop s)))))))

(def ^:private TPL "
template: prova_sweep_f5
contexto: compliance
dominio: tribunal_de_contas
parametros: { competencia: Competencia }
aplica_quando: verdadeiro
exige: parametro_tenant(\"remetido\")
prazo:
  janela: prazo_vigente(\"TCE-CE\", \"SIM_mensal\", competencia)
  a_partir_de: fim_de(competencia)
severidade: bloqueante
referencia_normativa: \"prova F5.2\"
")

(def ^:private reg-ver "registry-v1@2026-06-20")

(defn- seed! [ds repo-motor ente periodo data-limite remetido?]
  (jdbc/execute-one! ds ["DELETE FROM motor.prazo_dominio_vigente WHERE chave_periodo = ?" periodo])
  (rm/criar-prazo! repo-motor {:id (random-uuid) :dominio "tribunal_de_contas" :chave-dominio "TCE-CE"
                               :tipo-prazo "SIM_mensal" :chave-periodo periodo
                               :data-limite data-limite :fonte "IN 04/2019" :vigente true})
  (rm/criar-binding! repo-motor ente {:id (random-uuid) :ente-id ente :template-chave "prova_sweep_f5"
                                      :ativa true :parametros-tenant {"remetido" remetido?}}))

(defn- materializar! [repo-compliance ente objeto amb agora]
  (let [{:keys [registro-fatos repo-motor]} *sys*]
    (repo-compliance/avaliar-obrigacao! repo-compliance ente registro-fatos repo-motor
      {:regra (nuc/carregar-envelope TPL) :reg-ver reg-ver :objeto-tipo "competencia" :objeto-id objeto
       :amb amb :agora agora :origem "evento"})))

;; ---------- sweep transiciona pendente overdue -> vencida + audita ----------

(deftest sweep-vence-pendente-overdue
  (let [{:keys [datasource repo-motor repo-compliance]} *sys*
        ds (:ds datasource) ente (random-uuid) objeto (random-uuid)]
    (seed! ds repo-motor ente "2026-07" (LocalDate/of 2026 7 31) false)
    ;; materializa PENDENTE (agora ANTES do vencimento)
    (let [r (materializar! repo-compliance ente objeto {"competencia" {:ano 2026 :mes 7}}
                           (LocalDate/of 2026 7 1))
          oid (:id (:obrigacao r))]
      (is (= "pendente" (:estado (:obrigacao r))) "materializou pendente (dentro do prazo)")
      ;; o calendario avanca: hoje > vence_em -> o sweep deve vencer
      (let [transicionadas (repo-compliance/varrer-vencimentos! repo-compliance ente (LocalDate/of 2026 8 1))]
        (is (= [oid] (mapv :id transicionadas)) "o sweep transicionou a obrigacao overdue")
        (is (= "vencida" (:para (first transicionadas))) "pendente -> vencida")
        (tenancy/com-tenant* ds ente
          (fn [tx]
            (is (= "vencida" (:estado (db-obr/buscar tx ente oid))) "obrigacao persistida como vencida")
            (let [avals (db-aval/listar-da-obrigacao tx ente oid)
                  ult   (last avals)]
              (is (= 2 (count avals)) "materializacao + sweep = 2 avaliacoes (append-only)")
              (is (= "sweep" (:origem-avaliacao ult)) "a 2a avaliacao tem origem=sweep")
              (is (= "nao_conforme" (:veredito ult)) "veredito segue nao_conforme (nao foi cumprida)")
              (is (= "bloqueante" (:severidade ult)) "severidade da regra carregada (da ultima avaliacao)"))))))))

;; ---------- sweep nao toca obrigacao dentro do prazo ----------

(deftest sweep-nao-toca-no-prazo
  (let [{:keys [datasource repo-motor repo-compliance]} *sys*
        ds (:ds datasource) ente (random-uuid) objeto (random-uuid)]
    (seed! ds repo-motor ente "2099-07" (LocalDate/of 2099 7 31) false)
    (let [r   (materializar! repo-compliance ente objeto {"competencia" {:ano 2099 :mes 7}}
                             (LocalDate/of 2026 7 1))
          oid (:id (:obrigacao r))
          transicionadas (repo-compliance/varrer-vencimentos! repo-compliance ente (LocalDate/of 2026 8 1))]
      (is (empty? transicionadas) "obrigacao com vencimento no futuro NAO e' tocada")
      (tenancy/com-tenant* ds ente
        (fn [tx]
          (is (= "pendente" (:estado (db-obr/buscar tx ente oid))) "segue pendente")
          (is (= 1 (count (db-aval/listar-da-obrigacao tx ente oid))) "sem avaliacao de sweep espuria"))))))

;; ---------- sweep nao toca cumprida; idempotente (2a passada nao re-vence) ----------

(deftest sweep-ignora-cumprida-e-e-idempotente
  (let [{:keys [datasource repo-motor repo-compliance]} *sys*
        ds (:ds datasource) ente (random-uuid) obj-cumprida (random-uuid) obj-vencivel (random-uuid)]
    (seed! ds repo-motor ente "2026-06" (LocalDate/of 2026 6 30) true)   ; remetido=true -> cumprida
    (let [rc  (materializar! repo-compliance ente obj-cumprida {"competencia" {:ano 2026 :mes 6}}
                             (LocalDate/of 2026 6 15))
          oid-cumprida (:id (:obrigacao rc))]
      (seed! ds repo-motor ente "2026-05" (LocalDate/of 2026 5 31) false)  ; remetido=false, overdue
      (let [r2 (materializar! repo-compliance ente obj-vencivel {"competencia" {:ano 2026 :mes 5}}
                              (LocalDate/of 2026 5 1))
            oid (:id (:obrigacao r2))
            p1 (repo-compliance/varrer-vencimentos! repo-compliance ente (LocalDate/of 2026 8 1))
            p2 (repo-compliance/varrer-vencimentos! repo-compliance ente (LocalDate/of 2026 8 1))]
        (is (= [oid] (mapv :id p1)) "1a passada vence so a overdue nao-cumprida")
        (is (empty? p2) "2a passada e' idempotente (a ja-vencida nao re-transiciona)")
        (tenancy/com-tenant* ds ente
          (fn [tx]
            (is (= "cumprida" (:estado (db-obr/buscar tx ente oid-cumprida))) "a cumprida nao foi tocada pelo sweep")
            (is (= 2 (count (db-aval/listar-da-obrigacao tx ente oid))) "a vencida tem materializacao + 1 sweep (nao 2)")))))))
