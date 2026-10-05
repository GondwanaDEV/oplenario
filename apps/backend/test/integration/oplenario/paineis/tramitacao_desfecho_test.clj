(ns oplenario.paineis.tramitacao-desfecho-test
  "INTEGRACAO (PG real) — a lista interna de proposicoes e o quadro de tramitacao com o DESFECHO da materia (docs/16,
  retriagem linha 18). Nenhum ato depois do plenario move o `estado` do rito: a materia sancionada e ate' publicada
  como lei seguia 'Aguardando pauta' na lista e na coluna 'Pronta p/ pauta' do quadro. Prova, pelo Repo real do
  legislativo e pelo relay: a lista calcula o desfecho a partir do autografo; o quadro o projeta do evento (a votacao
  sozinha passa reto) e agrupa os totais por (estado, desfecho); e o backfill da migration reconstroi EXATAMENTE o que
  o caminho por evento projeta."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.legislativo.components.repositorio :as leg]
            [oplenario.legislativo.controllers :as controllers]
            [oplenario.legislativo.db.texto-versao :as texto]
            [oplenario.legislativo.db.votacao :as votacao]
            [oplenario.migracao :as migracao]
            [oplenario.paineis.components.repositorio :as paineis]
            [oplenario.paineis.diplomat.consumers :as consumers])
  (:import (java.time LocalDate)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *leg* nil)
(def ^:dynamic *paineis* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c) *leg* (leg/->RepoLegislativoPg c (outbox/bus)) *paineis* (paineis/->RepoPaineisPg c)]
        (try (t) (finally (component/stop c)))))))

(defn- drenar! [] (outbox/drenar! *ds* (consumers/registrar {})))
(defn- resolver-municipio [_] {:uf "CE" :municipio-nome "Fortaleza"})

(defn- protocolar! [ente]
  (let [pid (:id (leg/protocolar! *leg* ente {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                                              :municipio-nome "Fortaleza" :ementa "Dispoe sobre as hortas"
                                              :autor-tipo "vereador" :autor-texto "Fulana"}))]
    (leg/transacao *leg* ente
      (fn [tx]
        (let [{vid :id} (texto/nova-versao! tx {:id (random-uuid) :ente-id ente :proposicao-id pid
                                                :origem-versao "edicao" :texto-inline "TEXTO" :created-by nil})
              {:keys [lock-version]} (texto/buscar tx ente vid)]
          (texto/promover! tx {:ente-id ente :proposicao-id pid :versao-id vid :updated-by nil
                               :lock-version lock-version}))))
    pid))

(defn- aprovar! [ente pid]
  (let [vid (leg/transacao *leg* ente
              (fn [tx]
                (let [{vid :id} (votacao/abrir! tx {:id (random-uuid) :ente-id ente :objeto-tipo "proposicao"
                                                    :objeto-id pid :modalidade "nominal" :quorum-tipo "maioria_simples"})]
                  (votacao/registrar-voto! tx {:id (random-uuid) :ente-id ente :votacao-id vid
                                               :vereador-id (random-uuid) :voto "sim"})
                  vid)))]
    (leg/encerrar-votacao! *leg* ente {:id vid :base-membros 1 :updated-by nil :lock-version 0})))

(defn- autografo! [ente pid]
  (controllers/gerar-autografo *leg* resolver-municipio ente 2026 {:id (random-uuid) :proposicao-id pid :created-by nil})
  (:id (:tramitacao-executiva (leg/buscar-pos-aprovacao *leg* ente pid))))

(defn- vetar! [ente tid]
  (leg/registrar-resposta-executivo! *leg* ente {:id tid :resultado "vetado" :veto-tipo "total" :veto-razoes "x"
                                                 :updated-by nil :lock-version 0}))

(defn- ate-a-lei! [ente pid]
  (let [tid (autografo! ente pid)]
    (leg/registrar-resposta-executivo! *leg* ente {:id tid :resultado "sancionado" :updated-by nil :lock-version 0})
    (let [{nid :id} (controllers/promulgar-norma *leg* resolver-municipio ente (LocalDate/of 2026 10 5)
                                                 {:id (random-uuid) :proposicao-id pid :promulgado-por nil :created-by nil})]
      (controllers/publicar-norma *leg* ente nid {:veiculo-publicacao "DOM" :lock-version 0 :updated-by nil}))))

(defn- desfecho-na-lista [ente]
  (into {} (map (juxt :id :desfecho))
        (:itens (leg/listar-e-contar-proposicoes *leg* ente {:pagina 1 :tamanho 50}))))

(defn- quadro [ente]
  (let [{:keys [itens totais-por-estado]} (paineis/tramitacao-board *paineis* ente)]
    {:itens (into {} (map (juxt :proposicao-id :desfecho)) itens)
     :totais (set (map (juxt :estado :desfecho :n) totais-por-estado))}))

(deftest lista-e-quadro-contam-o-desfecho-a-partir-do-autografo
  (let [ente (random-uuid)
        parada (protocolar! ente)
        aprovada (protocolar! ente)
        vetada (protocolar! ente)
        lei (protocolar! ente)]
    (aprovar! ente aprovada)
    (aprovar! ente vetada)
    (vetar! ente (autografo! ente vetada))
    (aprovar! ente lei)
    (ate-a-lei! ente lei)
    (drenar!)
    (testing "a lista calcula o desfecho na leitura; a votacao sozinha nao decide"
      (is (= {parada nil aprovada nil vetada "vetado" lei "publicada"} (desfecho-na-lista ente))))
    (testing "o quadro projeta o mesmo do evento, e os totais separam por desfecho"
      (let [{:keys [itens totais]} (quadro ente)]
        (is (= {parada nil aprovada nil vetada "vetado" lei "publicada"} itens))
        (is (= #{["protocolada" nil 2] ["protocolada" "vetado" 1] ["protocolada" "publicada" 1]} totais))))
    (testing "drenar de novo nao muda nada"
      (drenar!)
      (is (= "publicada" (get (:itens (quadro ente)) lei))))))

(deftest o-quadro-nao-volta-atras-com-ato-antigo-reentregue
  (let [ente (random-uuid) pid (protocolar! ente)]
    (drenar!)
    (jdbc/with-transaction [tx *ds*]
      (paineis/projetar-evento! tx {:tipo "proposicao.desfecho-registrado" :ente-id ente
                                    :payload {:proposicao-id (str pid) :ato "sancionado" :ocorrido-em "2030-06-01T12:00:00Z"}})
      (paineis/projetar-evento! tx {:tipo "proposicao.desfecho-registrado" :ente-id ente
                                    :payload {:proposicao-id (str pid) :ato "autografo_enviado"
                                              :ocorrido-em "2030-05-01T12:00:00Z"}})
      (paineis/projetar-evento! tx {:tipo "proposicao.desfecho-registrado" :ente-id ente
                                    :payload {:proposicao-id (str pid) :ato "aprovada" :ocorrido-em "2030-07-01T12:00:00Z"}}))
    (is (= "sancionado" (get (:itens (quadro ente)) pid))
        "o ato mais antigo nao volta atras, e a votacao (mesmo mais nova) passa reto")))

;; ---------- o backfill da migration == o caminho por evento ----------

(def ^:private statements-da-migration
  (delay
    (->> (str/split (slurp (clojure.java.io/resource "migrations/20261005000211-paineis-tramitacao-desfecho.up.sql"))
                    #"--;;")
         (map #(str/trim (str/join "\n" (remove (fn [l] (str/starts-with? (str/trim l) "--")) (str/split-lines %)))))
         (remove str/blank?))))

(deftest o-backfill-reconstroi-exatamente-o-que-o-caminho-por-evento-projeta
  (let [ente (random-uuid) parada (protocolar! ente) vetada (protocolar! ente) lei (protocolar! ente)]
    (aprovar! ente vetada)
    (vetar! ente (autografo! ente vetada))
    (aprovar! ente lei)
    (ate-a-lei! ente lei)
    (drenar!)
    (let [tudo (fn [tx] (jdbc/execute! tx ["SELECT proposicao_id, desfecho, desfecho_em FROM paineis.tramitacao
                                            WHERE ente_id = ? ORDER BY proposicao_id" ente]))
          rodar! (fn [tx] (doseq [s @statements-da-migration] (jdbc/execute! tx [s]))
                   (jdbc/execute! tx ["ALTER TABLE paineis.tramitacao NO FORCE ROW LEVEL SECURITY"]))]
      (jdbc/with-transaction [tx *ds* {:rollback-only true}]
        (jdbc/execute! tx ["ALTER TABLE paineis.tramitacao NO FORCE ROW LEVEL SECURITY"])
        (let [antes (tudo tx)]
          (is (= 3 (count antes)))
          (is (= #{nil "vetado" "publicada"} (set (map :tramitacao/desfecho antes))))
          (testing "rodar o backfill POR CIMA do que o evento ja' projetou nao muda nada"
            (rodar! tx)
            (is (= antes (tudo tx))))
          (testing "apagado o desfecho, o backfill o reconstroi IGUAL (ato e instante)"
            (jdbc/execute! tx ["UPDATE paineis.tramitacao SET desfecho = NULL, desfecho_em = NULL WHERE ente_id = ?" ente])
            (rodar! tx)
            (is (= antes (tudo tx)))))))))
