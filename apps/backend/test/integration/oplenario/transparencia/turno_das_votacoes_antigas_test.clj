(ns oplenario.transparencia.turno-das-votacoes-antigas-test
  "INTEGRACAO (PG real) — a mig 20261005000280 reescreve no portal a votacao de turno gravada antes da regra da emenda a
  Lei Organica ('Aprovada em plenario' -> 'Aprovada em 1º turno'), com a mesma conta e o mesmo rotulo da projecao
  (`transparencia.logic.desfecho/rotulo`). Roda o SQL da migration numa tx que volta atras (rollback-only), sobre linhas
  semeadas na forma antiga."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.migracao :as migracao]
            [oplenario.transparencia.logic.desfecho :as desfecho])
  (:import (java.time Instant)))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)]
        (try (t) (finally (component/stop c)))))))

(def ^:private statements
  (delay
    (->> (str/split (slurp (clojure.java.io/resource "migrations/20261005000280-transparencia-turno-das-votacoes-antigas.up.sql"))
                    #"--;;")
         (map #(str/trim (str/join "\n" (remove (fn [l] (str/starts-with? (str/trim l) "--")) (str/split-lines %)))))
         (remove str/blank?))))

(defn- materia! [tx ente pid tipo]
  (jdbc/execute! tx ["INSERT INTO transparencia.materia (ente_id, proposicao_id, tipo, ano, sequencial, urn_lex, ementa, estado)
                      VALUES (?, ?, ?, 2026, 1, 'urn:x', 'Ementa', 'em_pauta')" ente pid tipo]))

(defn- linha! [tx ente pid instante chave etapa]
  (jdbc/execute! tx ["INSERT INTO transparencia.materia_movimentacao (ente_id, proposicao_id, ocorrido_em, etapa_chave, etapa, inicial)
                      VALUES (?, ?, ?, ?, ?, false)" ente pid (Instant/parse instante) chave etapa]))

(defn- linhas [tx ente pid]
  (mapv (juxt :etapa_chave :etapa)
        (jdbc/execute! tx ["SELECT etapa_chave, etapa FROM transparencia.materia_movimentacao
                            WHERE ente_id = ? AND proposicao_id = ? ORDER BY ocorrido_em" ente pid]
                       {:builder-fn rs/as-unqualified-maps})))

(deftest a-votacao-antiga-da-emenda-passa-a-dizer-o-turno
  (jdbc/with-transaction [tx *ds* {:rollback-only true}]
    (jdbc/execute! tx ["ALTER TABLE transparencia.materia NO FORCE ROW LEVEL SECURITY"])
    (jdbc/execute! tx ["ALTER TABLE transparencia.materia_movimentacao NO FORCE ROW LEVEL SECURITY"])
    (let [ente (random-uuid) pelom (random-uuid) dois (random-uuid) rejeitada (random-uuid) pl (random-uuid)]
      (doseq [[p t] [[pelom "proposta_emenda_lom"] [dois "proposta_emenda_lom"] [rejeitada "proposta_emenda_lom"]
                     [pl "projeto_lei"]]]
        (materia! tx ente p t)
        (linha! tx ente p "2026-09-01T12:00:00Z" "em_pauta" "Em Pauta"))
      ;; o caso de producao: uma aprovacao so', gravada como "Aprovada em plenario"
      (linha! tx ente pelom "2026-09-23T15:36:29Z" "ato:aprovada" "Aprovada em plenário")
      ;; duas aprovacoes antigas (e a redacao final, que nao e' turno)
      (linha! tx ente dois "2026-09-02T12:00:00Z" "ato:aprovada" "Aprovada em plenário")
      (linha! tx ente dois "2026-09-20T12:00:00Z" "ato:aprovada" "Aprovada em plenário")
      (linha! tx ente dois "2026-09-21T12:00:00Z" "ato:aprovada:redacao_final" "Redação final aprovada em plenário")
      ;; 1º turno ja' projetado com o turno, 2º turno antigo rejeitado
      (linha! tx ente rejeitada "2026-09-02T12:00:00Z" "ato:aprovada:turno_1" "Aprovada em 1º turno")
      (linha! tx ente rejeitada "2026-09-20T12:00:00Z" "ato:rejeitada" "Rejeitada em plenário")
      ;; projeto de lei: turno unico, nada muda
      (linha! tx ente pl "2026-09-02T12:00:00Z" "ato:aprovada" "Aprovada em plenário")
      (doseq [s @statements] (jdbc/execute! tx [s]))
      (testing "o caso de producao vira 1º turno"
        (is (= [["em_pauta" "Em Pauta"] ["ato:aprovada:turno_1" "Aprovada em 1º turno"]] (linhas tx ente pelom))))
      (testing "duas aprovacoes: 1º e 2º turno; a redacao final fica"
        (is (= [["em_pauta" "Em Pauta"]
                ["ato:aprovada:turno_1" "Aprovada em 1º turno"]
                ["ato:aprovada:turno_2" "Aprovada em 2º turno"]
                ["ato:aprovada:redacao_final" "Redação final aprovada em plenário"]]
               (linhas tx ente dois))))
      (testing "a rejeicao e' do turno que se votava, contando a aprovacao ja' projetada"
        (is (= [["em_pauta" "Em Pauta"]
                ["ato:aprovada:turno_1" "Aprovada em 1º turno"]
                ["ato:rejeitada:turno_2" "Rejeitada em 2º turno"]]
               (linhas tx ente rejeitada))))
      (testing "projeto de lei nao muda"
        (is (= [["em_pauta" "Em Pauta"] ["ato:aprovada" "Aprovada em plenário"]] (linhas tx ente pl))))
      (testing "o rotulo e a chave sao os da projecao"
        (is (= "Aprovada em 1º turno" (desfecho/rotulo {:ato "aprovada" :turno 1})))
        (is (= "ato:rejeitada:turno_2" (desfecho/chave {:ato "rejeitada" :turno 2}))))
      (testing "rodar de novo nao muda nada"
        (let [antes (linhas tx ente dois)]
          ;; a tabela temporaria e' ON COMMIT DROP e esta tx nao comita: a 2a rodada a recria
          (jdbc/execute! tx ["DROP TABLE turno_das_votacoes_antigas"])
          (doseq [s @statements] (jdbc/execute! tx [s]))
          (is (= antes (linhas tx ente dois))))))))
