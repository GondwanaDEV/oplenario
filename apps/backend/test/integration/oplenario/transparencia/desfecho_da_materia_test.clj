(ns oplenario.transparencia.desfecho-da-materia-test
  "INTEGRACAO (PG real) — o desfecho da materia (docs/16, retriagem linhas 18 e 30). Nenhum ato depois do plenario move
  o `estado` do rito: antes, a materia aprovada, sancionada e publicada como lei aparecia no portal como 'Aguardando
  pauta', e a linha do tempo publica e a ficha interna paravam no plenario. Prova a cadeia inteira pelo Repo real do
  legislativo (cada ato emite `proposicao.desfecho-registrado`; a publicacao segue em `norma.publicada`), o relay e a
  projecao do portal; a ordem que nao volta atras; e o backfill da migration
  reconstruindo EXATAMENTE o que o caminho por evento projeta."
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
            [oplenario.transparencia.components.repositorio :as transp]
            [oplenario.transparencia.db.movimentacao :as db-mov]
            [oplenario.transparencia.diplomat.consumers :as consumers])
  (:import (java.time LocalDate)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *leg* nil)
(def ^:dynamic *transp* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c) *leg* (leg/->RepoLegislativoPg c (outbox/bus)) *transp* (transp/->RepoTransparenciaPg c)]
        (try (t) (finally (component/stop c)))))))

(defn- drenar! [] (outbox/drenar! *ds* (consumers/registrar {})))
(defn- resolver-municipio [_] {:uf "CE" :municipio-nome "Fortaleza"})

(defn- protocolar! [ente & {:keys [tipo] :or {tipo "projeto_lei"}}]
  (let [pid (:id (leg/protocolar! *leg* ente {:id (random-uuid) :ente-id ente :tipo tipo :ano 2026 :uf "CE"
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

(defn- votar! [ente pid voto & {:keys [objeto-tipo aberta-em] :or {objeto-tipo "proposicao"}}]
  (let [vid (leg/transacao *leg* ente
              (fn [tx]
                (let [{vid :id} (votacao/abrir! tx {:id (random-uuid) :ente-id ente :objeto-tipo objeto-tipo
                                                    :objeto-id pid :modalidade "nominal" :quorum-tipo "maioria_simples"
                                                    :aberta-em aberta-em})]
                  (votacao/registrar-voto! tx {:id (random-uuid) :ente-id ente :votacao-id vid
                                               :vereador-id (random-uuid) :voto voto})
                  vid)))]
    ;; pelo Repo: e' ele que emite o desfecho (o db/ direto, como nos testes antigos, nao emite nada)
    (leg/encerrar-votacao! *leg* ente {:id vid :base-membros 1 :updated-by nil :lock-version 0})))

(defn- ate-a-lei!
  "Da materia JA' aprovada em plenario ate' a lei publicada, passando por veto e veto derrubado."
  [ente pid]
  (controllers/gerar-autografo *leg* resolver-municipio ente 2026 {:id (random-uuid) :proposicao-id pid :created-by nil})
  (let [tid (:id (:tramitacao-executiva (leg/buscar-pos-aprovacao *leg* ente pid)))]
    (leg/registrar-resposta-executivo! *leg* ente {:id tid :resultado "vetado" :veto-tipo "total"
                                                   :veto-razoes "inconstitucional" :updated-by nil :lock-version 0})
    (leg/apreciar-veto! *leg* ente {:id tid :resultado "veto_derrubado" :veto-votacao-id nil
                                    :updated-by nil :lock-version 1}))
  (let [{nid :id} (controllers/promulgar-norma *leg* resolver-municipio ente (LocalDate/of 2026 10 5)
                                               {:id (random-uuid) :proposicao-id pid :promulgado-por nil :created-by nil})]
    (controllers/publicar-norma *leg* ente nid {:veiculo-publicacao "Diario Oficial" :lock-version 0 :updated-by nil})))

(defn- materia [ente pid] (transp/buscar-materia *transp* ente pid))
(defn- etapas [ente pid] (->> (transp/movimentacoes-da-materia *transp* ente pid) :movimentacoes (map :etapa) reverse vec))

(deftest a-materia-vira-lei-e-o-portal-conta-o-caminho-inteiro
  (let [ente (random-uuid) pid (protocolar! ente)]
    (drenar!)
    (is (nil? (:desfecho (materia ente pid))) "antes do voto nao ha' desfecho: a situacao vem do rito")
    (votar! ente pid "sim")
    (drenar!)
    (is (= "aprovada" (:desfecho (materia ente pid))) "aprovada em plenario, nao mais 'aguardando pauta'")
    (is (= "protocolada" (:estado (materia ente pid))) "o estado do rito segue intocado: o desfecho e' outra coisa")
    (ate-a-lei! ente pid)
    (drenar!)
    (is (= "publicada" (:desfecho (materia ente pid))))
    (is (= ["Protocolada" "Aprovada em plenário" "Autógrafo nº 1/2026 enviado ao Executivo" "Vetada pelo Executivo"
            "Veto derrubado pela Câmara" "Promulgação: Lei nº 1/2026" "Publicação: Lei nº 1/2026"]
           (etapas ente pid)))
    (testing "drenar de novo nao duplica nada"
      (drenar!)
      (is (= 7 (count (etapas ente pid)))))))

(deftest rejeitada-em-plenario
  (let [ente (random-uuid) pid (protocolar! ente)]
    (votar! ente pid "nao")
    (drenar!)
    (is (= "rejeitada" (:desfecho (materia ente pid))))
    (is (= ["Protocolada" "Rejeitada em plenário"] (etapas ente pid)))))

(deftest redacao-final-tem-rotulo-proprio
  (let [ente (random-uuid) pid (protocolar! ente)]
    (votar! ente pid "sim")
    (votar! ente pid "sim" :objeto-tipo "redacao_final")
    (drenar!)
    (is (= ["Protocolada" "Aprovada em plenário" "Redação final aprovada em plenário"] (etapas ente pid)))))

(deftest emenda-a-lom-diz-o-turno-e-nao-que-foi-aprovada
  ;; CF art. 29: a emenda a Lei Organica vota em dois turnos. O 1o aprovado e' "Aprovada em 1º turno" — dizer
  ;; "Aprovada em plenário" ali seria dizer que a materia foi aprovada, e ela ainda nao foi.
  (let [ente (random-uuid) pid (protocolar! ente :tipo "proposta_emenda_lom")]
    (votar! ente pid "sim")
    (drenar!)
    (is (= ["Protocolada" "Aprovada em 1º turno"] (etapas ente pid)))
    (votar! ente pid "sim" :aberta-em (.plus (java.time.Instant/now) (java.time.Duration/ofDays 11)))
    (drenar!)
    (is (= ["Protocolada" "Aprovada em 1º turno" "Aprovada em 2º turno"] (etapas ente pid)))
    (is (= "aprovada" (:desfecho (materia ente pid))) "o desfecho segue o ato; o selo so' muda no autografo (FE)")))

(deftest votacao-de-outro-objeto-nao-e-desfecho-da-materia
  (let [ente (random-uuid) pid (protocolar! ente)]
    (votar! ente pid "sim" :objeto-tipo "requerimento")
    (drenar!)
    (is (nil? (:desfecho (materia ente pid))) "votacao de requerimento com o mesmo uuid nao aprova a materia")
    (is (= ["Protocolada"] (etapas ente pid)))))

(deftest ato-mais-antigo-reentregue-nao-volta-atras
  (let [ente (random-uuid) pid (protocolar! ente)]
    (drenar!)
    (jdbc/with-transaction [tx *ds*]
      (jdbc/execute! tx ["SELECT set_config('app.ente_id', ?, true)" (str ente)])
      (transp/projetar-evento! tx {:tipo "proposicao.desfecho-registrado" :ente-id ente
                                   :payload {:proposicao-id (str pid) :ato "sancionado"
                                             :ocorrido-em "2030-06-01T12:00:00Z"}})
      (transp/projetar-evento! tx {:tipo "proposicao.desfecho-registrado" :ente-id ente
                                   :payload {:proposicao-id (str pid) :ato "aprovada"
                                             :ocorrido-em "2030-05-01T12:00:00Z"}}))
    (is (= "sancionado" (:desfecho (materia ente pid))) "o ultimo ato vale, mesmo chegando antes do anterior")
    (is (= ["Protocolada" "Aprovada em plenário" "Sancionada pelo Executivo"] (etapas ente pid))
        "a linha do tempo ordena pelo instante do ato, nao pela chegada")))

;; ---------- o backfill da migration == o caminho por evento ----------

(def ^:private statements-da-migration
  (delay
    (->> (str/split (slurp (clojure.java.io/resource "migrations/20261005000210-transparencia-materia-desfecho.up.sql"))
                    #"--;;")
         (map #(str/trim (str/join "\n" (remove (fn [l] (str/starts-with? (str/trim l) "--")) (str/split-lines %)))))
         (remove str/blank?))))

(deftest o-backfill-reconstroi-exatamente-o-que-o-caminho-por-evento-projeta
  (let [ente (random-uuid) lei (protocolar! ente) rejeitada (protocolar! ente) parada (protocolar! ente)]
    (votar! ente lei "sim")
    (ate-a-lei! ente lei)
    (votar! ente rejeitada "nao")
    (drenar!)
    (let [linhas (fn [tx p] (mapv #(select-keys % [:ocorrido-em :etapa :inicial]) (db-mov/listar tx ente p)))
          desfechos (fn [tx] (jdbc/execute! tx ["SELECT proposicao_id, desfecho, desfecho_em FROM transparencia.materia
                                                 WHERE ente_id = ? ORDER BY proposicao_id" ente]))
          tudo (fn [tx] [(linhas tx lei) (linhas tx rejeitada) (linhas tx parada) (desfechos tx)])
          rodar! (fn [tx] (jdbc/execute! tx ["DROP TABLE IF EXISTS desfecho_backfill"])
                   (doseq [s @statements-da-migration] (jdbc/execute! tx [s])))]
      (jdbc/with-transaction [tx *ds* {:rollback-only true}]
        (jdbc/execute! tx ["ALTER TABLE transparencia.materia_movimentacao NO FORCE ROW LEVEL SECURITY"])
        (jdbc/execute! tx ["ALTER TABLE transparencia.materia NO FORCE ROW LEVEL SECURITY"])
        (let [antes (tudo tx)]
          (is (= 7 (count (first antes))))
          (is (= 1 (count (nth antes 2))) "materia que nao foi a votos: so' o protocolo")
          (testing "rodar o backfill POR CIMA do que o evento ja' projetou nao duplica nem muda nada"
            (rodar! tx)
            (jdbc/execute! tx ["ALTER TABLE transparencia.materia_movimentacao NO FORCE ROW LEVEL SECURITY"])
            (jdbc/execute! tx ["ALTER TABLE transparencia.materia NO FORCE ROW LEVEL SECURITY"])
            (is (= antes (tudo tx))))
          (testing "apagados os atos, o backfill os reconstroi IGUAIS (instante, rotulo, desfecho)"
            (jdbc/execute! tx ["DELETE FROM transparencia.materia_movimentacao WHERE ente_id = ? AND etapa_chave LIKE 'ato:%'" ente])
            (jdbc/execute! tx ["UPDATE transparencia.materia SET desfecho = NULL, desfecho_em = NULL WHERE ente_id = ?" ente])
            (rodar! tx)
            (jdbc/execute! tx ["ALTER TABLE transparencia.materia_movimentacao NO FORCE ROW LEVEL SECURITY"])
            (jdbc/execute! tx ["ALTER TABLE transparencia.materia NO FORCE ROW LEVEL SECURITY"])
            (is (= antes (tudo tx)))))))))
