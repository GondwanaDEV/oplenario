(ns oplenario.demo.contas-test
  "INTEGRACAO (PG real): `contas/semear!` — as prestacoes de contas da Casa da demo (ADR-0021 Parte B). O que importa:
  (1) as tres situacoes que a tela mostra (pronta para a pauta, julgada, acompanhamento) sairam pelo caminho de
  PRODUCAO (o PDL protocolado pelo registro, a notificacao congelando o prazo, a votacao encerrada gravando o resultado);
  (2) rodar de novo nao duplica prestacao, PDL, documento nem votacao.

  `with-sistema` reusada de `oplenario.demo.casa-test`, como os outros testes de `demo/`."
  (:require [casa]
            [clojure.test :refer [deftest is testing]]
            [contas :as contas-demo]
            [next.jdbc :as jdbc]
            [oplenario.demo.casa-test :refer [with-sistema]]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.repositorio-contas :as repo-contas]))

(defn- contar
  "Conta na tx da Casa (as tabelas sao FORCE RLS: sem o tenant, a contagem seria zero e o teste nao provaria nada)."
  [ds sql ente]
  (tenancy/com-tenant* ds ente #(-> (jdbc/execute-one! % [sql ente]) vals first long)))

(deftest prestacoes-de-contas-da-demo
  (with-sistema [s]
    (let [ds (:ds (:datasource s))
          {:keys [ente identidades]} (casa/semear! s)
          r1 (contas-demo/semear! s ente identidades)
          repo (:repo-legislativo s)]
      (testing "as tres situacoes"
        (is (= {:governo-2024 "pronta_para_pauta" :governo-2023 "julgada" :mesa-2024 "acompanhamento"} (:estados r1))))
      (testing "2024: PDL da comissao de financas, notificacao, parecer e defesa em PDF"
        (let [p (repo-contas/prestacao repo ente (:governo-2024 r1))]
          (is (= "projeto_decreto_legislativo" (get-in p [:proposicao :tipo])))
          (is (some? (:prazo-defesa-ate p)))
          (is (some? (:defesa-juntada-em p)))
          (is (= #{"parecer_previo" "defesa"} (set (map :tipo (:documentos p)))))))
      (testing "2023: julgada por votacao nominal 2/3, o parecer prevaleceu"
        (let [p (repo-contas/prestacao repo ente (:governo-2023 r1))]
          (is (= "parecer_mantido" (:resultado p)))
          (is (= 9 (get-in p [:votacao :total-sim])))
          (is (< (* 3 9) (* 2 (get-in p [:votacao :base-membros]))) "9 sim nao chega aos 2/3 da composicao")))
      (testing "idempotente: rodar de novo rele em vez de duplicar"
        (let [antes {:p (contar ds "select count(*) from legislativo.prestacao_contas where ente_id = ?" ente)
                     :d (contar ds "select count(*) from legislativo.prestacao_contas_documento where ente_id = ?" ente)
                     :pdl (contar ds "select count(*) from legislativo.proposicoes where ente_id = ? and tipo = 'projeto_decreto_legislativo'" ente)
                     :v (contar ds "select count(*) from legislativo.votacoes where ente_id = ? and quorum_tipo = 'maioria_qualificada_2_3'" ente)}
              r2 (contas-demo/semear! s ente identidades)]
          (is (= (dissoc r1 :estados) (dissoc r2 :estados)))
          (is (= antes {:p (contar ds "select count(*) from legislativo.prestacao_contas where ente_id = ?" ente)
                        :d (contar ds "select count(*) from legislativo.prestacao_contas_documento where ente_id = ?" ente)
                        :pdl (contar ds "select count(*) from legislativo.proposicoes where ente_id = ? and tipo = 'projeto_decreto_legislativo'" ente)
                        :v (contar ds "select count(*) from legislativo.votacoes where ente_id = ? and quorum_tipo = 'maioria_qualificada_2_3'" ente)})))))))
