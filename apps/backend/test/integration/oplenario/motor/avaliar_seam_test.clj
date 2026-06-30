(ns oplenario.motor.avaliar-seam-test
  "INTEGRACAO (PG real) — F2.3: o seam `motor/avaliar` avalia FORA do atom-fixture. Prova end-to-end:
  (a) FATO DE DOMINIO real — `populacao()` resolvido pelo RegistroFatos (resolver-para) lendo
  cadastros.ente⋈municipios sob a tx do tenant; (b) BUILTIN own-schema db-backed — `prazo_vigente(...)`
  lendo motor.prazo_dominio_vigente via RepoMotor; (c) FAIL-CLOSED — regra que referencia fato SEM fn
  registrada (remessa_enviada, módulo futuro) lança, nunca avalia errado (C1)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.db.estrutura :as estrutura]
            [oplenario.cadastros.db.referencia :as referencia]
            [oplenario.config :as config]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.motor.api :as motor]
            [oplenario.motor.components.repositorio :as rm]
            [oplenario.motor.nucleo :as nuc]
            [oplenario.motor.templates :as tpl]
            [oplenario.sistema :as sistema])
  (:import (java.time LocalDate)))

(def ^:dynamic *sys* nil)

(use-fixtures :once
  (fn [t]
    (let [s (component/start (sistema/novo-sistema (config/carregar)))]
      (migracao/migrar! (:ds (:datasource s)))
      (binding [*sys* s] (try (t) (finally (component/stop s)))))))

;; template VÁLIDO (type-check) que usa SÓ fatos com fn registrada (populacao) + builtin db (prazo_vigente).
(def ^:private TPL-SEAM "
template: prova_seam_f2
contexto: compliance
dominio: tribunal_de_contas
parametros: { competencia: Competencia }
aplica_quando: populacao() > 10000
exige: populacao() > 0
prazo:
  janela: prazo_vigente(\"TCE-CE\", \"SIM_mensal\", competencia)
  a_partir_de: fim_de(competencia)
severidade: bloqueante
referencia_normativa: \"prova F2.3\"
")

(deftest avaliar-com-fato-real-e-prazo-db
  (let [{:keys [datasource repo-motor registro-fatos]} *sys*
        ds (:ds datasource)
        ente (random-uuid)]
    ;; type-check primeiro (o seam só avalia regra VÁLIDA)
    (is (= "VALIDA" (:status (motor/verificar-fonte TPL-SEAM))) "template do seam tipa")
    ;; seed: municipio é REFERÊNCIA (sem ente_id) — semeada como DONO no :ds (o app role só a LÊ);
    ;; o ente é tenant → sob a tx do tenant.
    (referencia/inserir-municipio! ds {:codigo-ibge "2304400" :nome "Fortaleza" :uf "CE" :capital true :populacao 2703391})
    (tenancy/com-tenant* ds ente
      (fn [tx]
        (estrutura/inserir-ente! tx {:ente-id ente :municipio-ibge "2304400" :nome-oficial "Camara Municipal de Fortaleza"})))
    ;; seed: prazo de domínio db-backed (período isolado p/ não colidir com outros testes)
    (jdbc/execute-one! ds ["DELETE FROM motor.prazo_dominio_vigente WHERE chave_periodo = '2099-12'"])
    (rm/criar-prazo! repo-motor {:id (random-uuid) :dominio "tribunal_de_contas" :chave-dominio "TCE-CE"
                                 :tipo-prazo "SIM_mensal" :chave-periodo "2099-12"
                                 :data-limite (LocalDate/of 2099 12 31) :fonte "IN 04/2019" :vigente true})
    ;; avalia sob a tx do tenant (os fatos leem a Casa corrente)
    (let [r (tenancy/com-tenant* ds ente
              (fn [tx]
                (motor/avaliar {:registro registro-fatos :repo-motor repo-motor :tx tx :ente-id ente
                                :regra (nuc/carregar-envelope TPL-SEAM) :reg-ver "registry-v1@2026-06-20"
                                :objeto-tipo "competencia" :objeto-id "2099-12"
                                :amb {"competencia" {:ano 2099 :mes 12}} :agora (LocalDate/of 2026 6 19)})))
          obrig (first (:obrigacoes r))]
      (is (= "conforme" (:veredito (:avaliacao r))) "populacao(2.7M)>0 → conforme (fato real do cadastros)")
      (is (= (LocalDate/of 2099 12 31) (:vence-em obrig)) "vence_em lido de motor.prazo_dominio_vigente (db-backed)")
      (is (= "IN 04/2019" (:prazo-fonte-ref obrig)) "prazo_fonte_ref capturado da fonte do db"))))

(deftest avaliar-fato-sem-fn-e-fail-closed
  (let [{:keys [datasource repo-motor registro-fatos]} *sys*
        ds (:ds datasource)
        ente (random-uuid)]
    ;; CONTINUA exige publicada_no_portal(despesa) — assinatura existe no catálogo, mas NENHUMA fn registrada
    ;; (módulo `transparencia` futuro). O resolver-para lança em runtime: fail-closed (nunca avalia errado).
    ;; NOTA: `remessa_enviada` SAIU desta lista na F5.3a — o compliance/relacoes a registra (a costura); o
    ;; guardião do fail-closed migrou p/ um fato ainda-não-registrado.
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fato sem fn registrada"
          (tenancy/com-tenant* ds ente
            (fn [tx]
              (motor/avaliar {:registro registro-fatos :repo-motor repo-motor :tx tx :ente-id ente
                              :regra (nuc/carregar-envelope tpl/CONTINUA) :reg-ver "registry-v1@2026-06-20"
                              :objeto-tipo "ato_despesa" :objeto-id "d1"
                              :amb {"despesa" {:id "d1"}} :agora (LocalDate/of 2026 6 19)}))))
        "publicada_no_portal sem fn → fail-closed")))
