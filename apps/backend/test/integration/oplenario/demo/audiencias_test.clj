(ns oplenario.demo.audiencias-test
  "INTEGRACAO (PG real): `audiencias/semear!` — as audiencias publicas da Casa da demo (ADR-0021 Parte A). Prova: a
  tematica AGENDADA daqui a 7 dias com 2 inscricoes (uma da Mesa, uma da cidada pelo portal, com o nome da identidade
  dela); a de metas fiscais `2026-Q1` ENCERRADA, com 2 cidadaos que falaram e a ata publicada; e que rodar de novo nao
  duplica nada. `with-sistema` reusada de `oplenario.demo.casa-test`, como os outros testes de `demo/`."
  (:require [audiencias :as audiencias-demo]
            [casa]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [oplenario.demo.casa-test :refer [with-sistema]]
            [oplenario.sessoes.components.repositorio :as repo-s]
            [oplenario.sessoes.components.repositorio-audiencia :as repo-aud])
  (:import (java.time Duration Instant LocalTime ZoneId)))

(defn- contar [ds sql ente] (-> (jdbc/execute-one! ds [sql ente]) vals first long))

(deftest audiencias-da-demo
  (with-sistema [s]
    (let [ds (:ds (:datasource s))
          repo (:repo-sessoes s)
          {:keys [ente identidades]} (casa/semear! s)
          ;; a semente agenda a tematica so' na 1a vez, para "hoje + 7 dias"; num banco persistente ela ja existe de
          ;; uma corrida anterior e a distancia ate hoje encolhe um dia por dia (quebrava no 2o dia, PR #183)
          criada-agora? (nil? (repo-s/buscar-sessao repo ente audiencias-demo/id-tematica))
          r (audiencias-demo/semear! s ente identidades)]
      (testing "a tematica, agendada para daqui a uma semana, com a fila"
        (let [sessao (repo-s/buscar-sessao repo ente (:tematica r))
              {:keys [audiencia inscricoes]} (repo-aud/audiencia-da-sessao repo ente (:tematica r))
              dias (.toDays (Duration/between (Instant/now) ^Instant (:agendada-para sessao)))]
          (is (= "audiencia_publica" (:tipo-sessao sessao)))
          (is (= "agendada" (:estado sessao)))
          (when criada-agora? (is (<= 6 dias 7)))
          (is (= (LocalTime/of 14 0) (.toLocalTime (.atZone ^Instant (:agendada-para sessao) (ZoneId/of "America/Fortaleza"))))
              "as 14h no fuso da Casa")
          (is (= "tematica" (:finalidade audiencia)))
          (is (= [["presencial_secretaria" "José Raimundo Lima" "entidade"]
                  ["portal_govbr" "Roberta Costa Aguiar" "individual"]]
                 (map (juxt :origem :nome :fala-como) inscricoes))
              "a Mesa inscreveu um; a cidada da demo, pelo portal, com o nome da identidade")
          (is (= (:cidadao identidades) (:identidade-id (second inscricoes))))
          (is (every? #(re-matches #"AUD-\d{4}-\d{6}" (:protocolo %)) inscricoes))))
      (testing "as metas fiscais do 1o quadrimestre: encerrada, 2 falaram, ata publicada"
        (let [sessao (repo-s/buscar-sessao repo ente (:metas-fiscais r))
              {:keys [audiencia inscricoes ata-publicada]} (repo-aud/audiencia-da-sessao repo ente (:metas-fiscais r))]
          (is (= "encerrada" (:estado sessao)))
          (is (= ["metas_fiscais" "2026-Q1"] ((juxt :finalidade :referencia) audiencia)))
          (is (= ["falou" "falou"] (map :estado inscricoes)))
          (is (every? :tempo-usado-segundos inscricoes))
          (is (true? ata-publicada))))
      (testing "idempotente: rodar de novo rele em vez de duplicar"
        (let [antes {:s (contar ds "select count(*) from sessoes.audiencia where ente_id = ?" ente)
                     :i (contar ds "select count(*) from sessoes.inscricao_cidadao where ente_id = ?" ente)
                     :a (contar ds "select count(*) from sessoes.ata a join sessoes.audiencia x
                                      on x.ente_id = a.ente_id and x.sessao_id = a.sessao_id where a.ente_id = ?" ente)}]
          (is (= r (audiencias-demo/semear! s ente identidades)))
          (is (= antes {:s (contar ds "select count(*) from sessoes.audiencia where ente_id = ?" ente)
                        :i (contar ds "select count(*) from sessoes.inscricao_cidadao where ente_id = ?" ente)
                        :a (contar ds "select count(*) from sessoes.ata a join sessoes.audiencia x
                                         on x.ente_id = a.ente_id and x.sessao_id = a.sessao_id where a.ente_id = ?" ente)}))
          (is (= {:s 2 :i 4 :a 1} antes)))))))
