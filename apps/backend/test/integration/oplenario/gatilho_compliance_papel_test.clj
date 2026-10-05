(ns oplenario.gatilho-compliance-papel-test
  "INTEGRACAO (PG real): o gatilho de compliance com o role de RUNTIME (`oplenario_pool`, NOBYPASSRLS, nao-dono). Os
  outros testes do gatilho conectam como o dono e nao viam que o app nunca recebeu grant no catalogo do motor: no CI
  da Trilha 3 a leitura do painel logava `permission denied for table template_compliance` e nenhuma obrigacao era
  avaliada. O dono so' migra (e semeia o catalogo, como o `migrate` de producao); tudo o mais roda como o pool.
  Prova tambem que o app so' LE o catalogo: escrever nele continua negado."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.relacoes.cadastro :as rel-cad]
            [oplenario.compliance.components.repositorio :as repo-compliance]
            [oplenario.compliance.relacoes :as rel-compliance]
            [oplenario.config :as config]
            [oplenario.gatilho-compliance :as gatilho]
            [oplenario.identidade.relacoes.identidade :as rel-id]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.legislativo.relacoes :as rel-legis]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.registro-fatos :as rf]
            [oplenario.motor.components.repositorio :as repo-motor]
            [oplenario.sessoes.relacoes.audiencia :as rel-aud]
            [oplenario.sessoes.relacoes.presenca :as rel-presenca])
  (:import (java.time LocalDate)
           (org.postgresql.util PSQLException)))

(def ^:dynamic *pool* nil)
(def ^:dynamic *deps* nil)

(def hoje (LocalDate/parse "2026-10-03"))

(use-fixtures :once
  (fn [t]
    (let [cfg (config/carregar)
          dono (component/start (datasource/datasource cfg))]
      (migracao/migrar! (:ds dono))
      (gatilho/garantir-catalogo! (repo-motor/->RepoMotorPg dono))
      (let [pool (component/start (datasource/datasource (update cfg :db assoc :user "oplenario_pool"
                                                                 :password "oplenario_dev_pool")))
            reg (component/start (rf/registro-fatos (merge rel-cad/relacoes rel-id/relacoes rel-presenca/relacoes
                                                           rel-aud/relacoes rel-legis/relacoes rel-compliance/relacoes)))]
        (binding [*pool* (:ds pool)
                  *deps* {:repo-compliance (repo-compliance/->RepoCompliancePg pool)
                          :repo-motor (repo-motor/->RepoMotorPg pool)
                          :repo-legislativo (repo-leg/->RepoLegislativoPg pool (outbox/bus))
                          :registro-fatos reg
                          :hoje (constantly hoje)}]
          (try (t) (finally (component/stop reg) (component/stop pool) (component/stop dono))))))))

(deftest o-gatilho-roda-com-o-role-de-runtime
  (let [ente (random-uuid)]
    (testing "as duas partes e a das remessas rodam sem erro de permissao, como na leitura do painel"
      (let [r (gatilho/disparar! *deps* ente {:origem "sob_demanda" :partes gatilho/partes-todas})]
        (is (map? r))
        (is (contains? r :vencidas))))
    (testing "o vinculo da Casa a regra nasceu (o app escreve o que e' da Casa)"
      (is (some? (repo-motor/binding-do-ente (:repo-motor *deps*) ente gatilho/chave-metas-fiscais))))))

(deftest o-catalogo-do-motor-e-so-leitura-para-o-app
  (testing "o pool le o template vigente"
    (is (some? (repo-motor/template-vigente (:repo-motor *deps*) gatilho/chave-metas-fiscais))))
  (testing "mas nao escreve no catalogo, que e' de todas as Casas"
    (is (thrown? PSQLException
                 (jdbc/execute-one! *pool* ["update motor.template_compliance set descricao = descricao"])))
    (is (thrown? PSQLException
                 (jdbc/execute-one! *pool* ["delete from motor.template_compliance where false"])))))
