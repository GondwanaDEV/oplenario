(ns oplenario.motor-db-test
  "INTEGRACAO (PG real) — F2.1: o motor/db real DISPONIBILIZADO como Stuart Sierra Component (RepoMotor,
  ADR-0001 §3-bis) sobre as 5 tabelas estaticas do schema `motor` (§22.7.6 Eixo B). Prova: as 4 tabelas
  de DOMINIO (template_compliance/prazo_dominio_vigente/calendario_feriado/registry_catalogo_versao)
  leem direto no :ds; a UNICA tabela TENANT (compliance_regra_tenant, FORCE RLS retrofitada em F1.0)
  le/escreve via com-tenant* e ISOLA por ente. Toda entidade/projecao confere o model interno (Malli,
  §22.10 models/). Conteudo regulatorio = fixture ([GAP] §22.7.5)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.repositorio :as rm]
            [oplenario.motor.models.catalogo :as mod]
            [oplenario.sistema :as sistema])
  (:import (java.time LocalDate)))

(def ^:dynamic *sys* nil)

(use-fixtures :once
  (fn [t]
    (let [s (component/start (sistema/novo-sistema (config/carregar)))]
      (migracao/migrar! (:ds (:datasource s)))
      ;; as 4 tabelas de DOMINIO persistem entre runs (sem rollback de tenant); limpa p/ isolar o run.
      ;; (a 5a, compliance_regra_tenant, ja isola por ente-id random + ON CONFLICT idempotente.)
      (jdbc/execute-one! (:ds (:datasource s))
        ["TRUNCATE motor.template_compliance, motor.prazo_dominio_vigente,
          motor.calendario_feriado, motor.registry_catalogo_versao, motor.compliance_regra_tenant"])
      (binding [*sys* s] (try (t) (finally (component/stop s)))))))

(defn- template-fixture [chave dominio chave-dominio]
  {:id (random-uuid) :chave-template chave :versao 1 :dominio dominio :chave-dominio chave-dominio
   :descricao (str "fixture " chave) :severidade "bloqueante" :referencia-normativa "n/a"
   :fonte-yaml "template: x" :forma-compilada {:t "lit" :valor true}
   :assinatura-parametros {"competencia" "Competencia"}
   :registry-versao-ref "registry-v1@2026-06-20" :estado-versao "vigente"})

;; ---------------------------------------------------------------------------
;; DOMINIO (sobre :ds): template_compliance, prazo, calendario, registry-versao
;; ---------------------------------------------------------------------------
(deftest template-compliance-vigentes-por-dominio
  (let [repo (:repo-motor *sys*)]
    ;; tribunal_de_contas escopado por jurisdicao (chave_dominio = codigo do TCE)
    (rm/criar-template! repo (template-fixture "remessa_mensal_sim" "tribunal_de_contas" "TCE-CE"))
    ;; federal: chave_dominio NULL (a armadilha do COALESCE — NULL != NULL deixaria escapar)
    (rm/criar-template! repo (template-fixture "transparencia_lrf" "federal" nil))
    (let [tce (rm/templates-vigentes repo "tribunal_de_contas" "TCE-CE")
          fed (rm/templates-vigentes repo "federal" nil)]
      (is (= #{"remessa_mensal_sim"} (set (map :chave-template tce))) "vigente do TCE-CE resolvido por escopo")
      (is (= #{"transparencia_lrf"} (set (map :chave-template fed))) "federal (chave_dominio NULL) resolvido via COALESCE")
      (is (= {:t "lit" :valor true} (:forma-compilada (first tce))) "forma_compilada jsonb desserializada")
      (is (m/validate mod/Template (first tce)) "template lido bate o model interno (resolucao por escopo)")
      (is (m/validate mod/Template (first fed)) "template federal (chave-dominio nil) bate o model")
      (is (empty? (rm/templates-vigentes repo "tribunal_de_contas" "TCE-SP")) "outro TCE nao ve a regra do CE"))))

(deftest prazo-dominio-vigente-honra-flag-vigente
  (let [repo (:repo-motor *sys*)
        nao-vigente {:id (random-uuid) :dominio "tribunal_de_contas" :chave-dominio "TCE-CE"
                     :tipo-prazo "SIM_mensal" :chave-periodo "2026-05"
                     :data-limite (LocalDate/of 2026 6 15) :fonte "IN 04/2019" :vigente false}
        deslizado   {:id (random-uuid) :dominio "tribunal_de_contas" :chave-dominio "TCE-CE"
                     :tipo-prazo "SIM_mensal" :chave-periodo "2026-05"
                     :data-limite (LocalDate/of 2026 6 30) :fonte "OC 16/2026" :vigente true}]
    ;; entidade de insert confere o model ANTES de persistir (Malli no input)
    (is (m/validate mod/Prazo nao-vigente) "entidade de prazo (norma-base) bate o model")
    (is (m/validate mod/Prazo deslizado) "entidade de prazo (deslize por circular) bate o model")
    ;; norma-base + deslize por Oficio Circular: so a 'vigente' vale (S3 prazo deslizante)
    (rm/criar-prazo! repo nao-vigente)
    (rm/criar-prazo! repo deslizado)
    (let [p (rm/prazo-vigente repo "tribunal_de_contas" "TCE-CE" "SIM_mensal" "2026-05")]
      (is (m/validate mod/PrazoVigente p) "projecao lida bate o model (contrato do resolvedor)")
      (is (= (LocalDate/of 2026 6 30) (:data-limite p)) "le a linha vigente (deslizada)")
      (is (= "OC 16/2026" (:fonte p)) "captura a fonte p/ prazo_fonte_ref (S1/S3)")
      (is (nil? (rm/prazo-vigente repo "tribunal_de_contas" "TCE-CE" "SIM_mensal" "2099-01")) "periodo sem prazo = nil (caller trata GAP)"))))

(deftest calendario-feriado-set-de-datas
  (let [repo (:repo-motor *sys*)
        feriado {:id (random-uuid) :jurisdicao "nacional" :municipio-id nil
                 :data (LocalDate/of 2026 9 7) :descricao "Independencia"}]
    (is (m/validate mod/Feriado feriado) "entidade de feriado bate o model")
    (rm/criar-feriado! repo feriado)
    (let [fs (rm/feriados repo "nacional" nil)]
      (is (m/validate [:set mod/Data] fs) "o read e' um set de LocalDate (a chave de proximo_dia_util)")
      (is (contains? fs (LocalDate/of 2026 9 7)) "feriado nacional no set"))))

(deftest registry-catalogo-versao-roundtrip
  (let [repo (:repo-motor *sys*)
        entidade {:id (random-uuid) :versao (str "registry-test@" (random-uuid)) :hash "abc" :descricao "teste"}]
    (is (m/validate mod/VersaoCatalogo entidade) "entidade de versao do catalogo bate o model")
    (rm/registrar-versao-catalogo! repo entidade)
    (let [lido (rm/versao-catalogo repo (:versao entidade))]
      (is (m/validate mod/VersaoCatalogo lido) "versao lida bate o model")
      (is (= (:versao entidade) (:versao lido)) "versao do catalogo persistida/lida"))))

;; ---------------------------------------------------------------------------
;; TENANT (via com-tenant*): compliance_regra_tenant — RLS isola por ente
;; ---------------------------------------------------------------------------
(deftest binding-tenant-isola-por-ente
  (let [repo (:repo-motor *sys*)
        ente-a (random-uuid) ente-b (random-uuid)
        binding-a {:id (random-uuid) :ente-id ente-a :template-chave "publicacao_ato_legislativo"
                   :ativa true :parametros-tenant {"prazo_publicacao_ato_dias" 5}}]
    (is (m/validate mod/RegraTenant binding-a) "entidade de binding bate o model")
    (rm/criar-binding! repo ente-a binding-a)
    (let [b (rm/binding-do-ente repo ente-a "publicacao_ato_legislativo")]
      (is (m/validate mod/RegraTenant b) "binding lido (via com-tenant*) bate o model")
      (is (true? (:ativa b)) "binding do ente A lido via com-tenant*")
      (is (= 5 (get (:parametros-tenant b) "prazo_publicacao_ato_dias")) "parametro_tenant com chave-string (casa com a DSL)"))
    (is (nil? (rm/binding-do-ente repo ente-b "publicacao_ato_legislativo")) "ente B NAO ve o binding de A (RLS)")))
