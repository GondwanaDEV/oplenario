(ns oplenario.legislativo.promulgar-norma-test
  "INTEGRACAO (PG real): 'Promulgar a lei' e 'Registrar publicacao' (F3.8b) pelo CONTROLLER, sobre o Repo real.
  A camada de dados (db/norma) ja' tinha cobertura; o que faltava era o caminho que a tela usa: a materia
  sancionada vira norma com o texto do autografo, uma vez so', e so' depois do desfecho que promulga."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.legislativo.controllers :as controllers]
            [oplenario.legislativo.db.proposicao :as prop]
            [oplenario.legislativo.db.texto-versao :as texto]
            [oplenario.legislativo.db.votacao :as votacao]
            [oplenario.migracao :as migracao])
  (:import (java.time LocalDate)))

(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*repo* (repo/->RepoLegislativoPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

(def ^:private hoje (LocalDate/of 2026 10 5))
(defn- resolver-municipio [_ente-id] {:uf "CE" :municipio-nome "Fortaleza"})

(defn- ate-o-executivo!
  "Protocola um PL com texto, a Camara aprova, gera o autografo (texto VOTADO) e abre a tramitacao no Executivo.
  Devolve {:proposicao-id :autografo :tramitacao-id}."
  [ente]
  (let [pid (:id (repo/transacao *repo* ente
                   (fn [tx] (prop/protocolar! tx {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                                                  :uf "CE" :municipio-nome "Fortaleza"
                                                  :ementa "Dispoe sobre as hortas comunitarias"}))))]
    (repo/transacao *repo* ente
      (fn [tx]
        (let [{vid :id} (texto/nova-versao! tx {:id (random-uuid) :ente-id ente :proposicao-id pid
                                                :origem-versao "edicao" :texto-inline "TEXTO VOTADO" :created-by nil})
              {:keys [lock-version]} (texto/buscar tx ente vid)]
          (texto/promover! tx {:ente-id ente :proposicao-id pid :versao-id vid :updated-by nil :lock-version lock-version}))))
    (repo/transacao *repo* ente
      (fn [tx]
        (let [{vid :id} (votacao/abrir! tx {:id (random-uuid) :ente-id ente :objeto-tipo "proposicao" :objeto-id pid
                                            :modalidade "nominal" :quorum-tipo "maioria_simples"})]
          (votacao/registrar-voto! tx {:id (random-uuid) :ente-id ente :votacao-id vid :vereador-id (random-uuid) :voto "sim"})
          (votacao/encerrar! tx {:id vid :ente-id ente :base-membros 1 :updated-by nil :lock-version 0}))))
    (controllers/gerar-autografo *repo* resolver-municipio ente 2026
                                 {:id (random-uuid) :proposicao-id pid :created-by nil})
    (let [{:keys [autografo tramitacao-executiva]} (repo/buscar-pos-aprovacao *repo* ente pid)]
      {:proposicao-id pid :autografo autografo :tramitacao-id (:id tramitacao-executiva)})))

(defn- sancionar! [ente tramitacao-id]
  (repo/registrar-resposta-executivo! *repo* ente
    {:id tramitacao-id :resultado "sancionado" :updated-by nil :lock-version 0}))

(defn- promulgar [ente pid]
  (controllers/promulgar-norma *repo* resolver-municipio ente hoje
                               {:id (random-uuid) :proposicao-id pid :promulgado-por nil :created-by nil}))

(defn- conflito [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e {:tipo (:tipo (ex-data e)) :msg (ex-message e)})))

(deftest a-materia-sancionada-vira-lei-com-o-texto-do-autografo
  (let [ente (random-uuid)
        {:keys [proposicao-id autografo tramitacao-id]} (ate-o-executivo! ente)]
    (sancionar! ente tramitacao-id)
    (let [r (promulgar ente proposicao-id)
          {:keys [norma]} (repo/buscar-pos-aprovacao *repo* ente proposicao-id)]
      (is (= 1 (:numero r)) "primeira lei de 2026 desta Casa")
      (is (= "urn:lex:br;ce;fortaleza:lei:2026-10-05;1" (:urn r)))
      (is (= "promulgada" (:estado norma)))
      (is (= "lei" (:tipo-norma norma)) "projeto de lei vira lei")
      (is (= "Dispoe sobre as hortas comunitarias" (:ementa norma)))
      (is (= (:texto-versao-id autografo) (:texto-versao-id norma)) "o texto que foi ao Executivo e' o que vira lei")
      (is (= (:id autografo) (:autografo-id norma))))))

(deftest promulgar-duas-vezes-e-recusado-sem-queimar-numero
  (let [ente (random-uuid)
        a (ate-o-executivo! ente)
        b (ate-o-executivo! ente)]
    (sancionar! ente (:tramitacao-id a))
    (sancionar! ente (:tramitacao-id b))
    (promulgar ente (:proposicao-id a))
    (is (= {:tipo :conflito/norma :msg "esta materia ja foi promulgada"}
           (conflito #(promulgar ente (:proposicao-id a)))))
    (is (= 2 (:numero (promulgar ente (:proposicao-id b)))) "a recusa nao consumiu o numero 2")))

(deftest so-promulga-depois-do-desfecho-que-promulga
  (let [ente (random-uuid)
        {:keys [proposicao-id tramitacao-id]} (ate-o-executivo! ente)]
    (testing "o Executivo ainda nao respondeu"
      (is (= {:tipo :conflito/norma :msg "ainda nao se promulga: o Executivo ainda nao respondeu ao autografo"}
             (conflito #(promulgar ente proposicao-id)))))
    (testing "vetado e o veto ainda nao apreciado"
      (repo/registrar-resposta-executivo! *repo* ente
        {:id tramitacao-id :resultado "vetado" :veto-tipo "total" :veto-razoes "inconstitucional"
         :updated-by nil :lock-version 0})
      (is (= {:tipo :conflito/norma :msg "ainda nao se promulga: o veto ainda nao foi apreciado pela Camara"}
             (conflito #(promulgar ente proposicao-id)))))
    (is (nil? (:norma (repo/buscar-pos-aprovacao *repo* ente proposicao-id))) "nada foi escrito")))

(deftest materia-sem-autografo-nao-promulga-e-inexistente-e-nil
  (let [ente (random-uuid)
        pid (:id (repo/transacao *repo* ente
                   (fn [tx] (prop/protocolar! tx {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                                                  :uf "CE" :municipio-nome "Fortaleza" :ementa "Sem autografo"}))))]
    (is (= :conflito/norma (:tipo (conflito #(promulgar ente pid)))))
    (is (nil? (promulgar ente (random-uuid))) "proposicao inexistente -> nil (404 na borda)")))

(deftest registrar-publicacao-uma-vez-com-o-veiculo
  (let [ente (random-uuid)
        {:keys [proposicao-id tramitacao-id]} (ate-o-executivo! ente)]
    (sancionar! ente tramitacao-id)
    (let [{nid :id} (promulgar ente proposicao-id)
          publicar #(controllers/publicar-norma *repo* ente nid
                      {:veiculo-publicacao "Diario Oficial do Municipio, ed. 1.234" :lock-version % :updated-by nil})]
      (is (= :conflito/norma (:tipo (conflito #(publicar 7)))) "lock velho -> conflito, nada muda")
      (publicar 0)
      (let [n (controllers/buscar-norma *repo* ente nid)]
        (is (= "publicada" (:estado n)))
        (is (= "Diario Oficial do Municipio, ed. 1.234" (:veiculo-publicacao n)))
        (is (some? (:publicado-em n))))
      (is (= :conflito/norma (:tipo (conflito #(publicar 1)))) "publicar de novo -> conflito")
      (is (nil? (controllers/publicar-norma *repo* ente (random-uuid)
                  {:veiculo-publicacao "DOM" :lock-version 0 :updated-by nil}))
          "norma inexistente -> nil (404 na borda)"))))
