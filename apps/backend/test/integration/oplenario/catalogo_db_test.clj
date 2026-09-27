(ns oplenario.catalogo-db-test
  "INTEGRACAO (PG real): ADR-0009 / B.1 — as tres primeiras ferramentas do catalogo executando contra os
  repositorios de verdade, pelo caminho que o agente usara' (`oplenario.catalogo/executar!`): a materia achada pelo
  numero que uma pessoa fala, o tenant respeitado, a pauta da sessao da vez."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.catalogo :as catalogo]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.legislativo.db.proposicao :as proposicao]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.components.repositorio :as repo-sessoes]
            [oplenario.sessoes.db.sessao :as sessao])
  (:import (java.time Instant)))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- deps []
  {:repo-legislativo (repo-leg/map->RepoLegislativoPg {:datasource {:ds *ds*}})
   :repo-sessoes (repo-sessoes/map->RepoSessoesPg {:datasource {:ds *ds*}})})

(defn- ator [ente papel] {:identidade-id (random-uuid) :ente-id ente :papeis #{papel}})

(defn- proposicao! [ente ementa]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (proposicao/protocolar! tx {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                                         :uf "CE" :municipio-nome "Fortaleza" :ementa ementa
                                         :autor-texto "Ver. Ana"}))))

(defn- sessao! [ente quando]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (:id (sessao/agendar! tx {:id (random-uuid) :ente-id ente :sessao-legislativa-id (random-uuid)
                                       :tipo-sessao "ordinaria" :modalidade "presencial"
                                       :agendada-para (Instant/parse quando)})))))

(defn- tipo-do-erro [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:tipo (ex-data e)))))

(deftest situacao-da-materia-pelo-numero-que-a-pessoa-fala
  (let [ente (random-uuid)
        p (proposicao! ente "Dispoe sobre a merenda escolar.")
        num (:sequencial p)
        pelo-numero {:tipo "projeto_lei" :sequencial num :ano 2026}]
    (testing "pelo numero (como o JSON do agente chega) e pelo id dao a mesma materia"
      (let [r (catalogo/executar! (deps) :secretaria (ator ente "secretario") "situacao_da_materia" pelo-numero)]
        (is (= (str (:id p)) (:id r)))
        (is (= "Dispoe sobre a merenda escolar." (:ementa r))))
      (is (= (str (:id p)) (:id (catalogo/executar! (deps) :vereador (ator ente "vereador") "situacao_da_materia"
                                              {:proposicao-id (str (:id p))})))))
    (testing "a materia de outra Casa nao existe para quem pergunta"
      (is (nil? (catalogo/executar! (deps) :secretaria (ator (random-uuid) "secretario") "situacao_da_materia"
                                    pelo-numero))))
    (testing "sem id nem numero completo e' entrada invalida"
      (is (= :validacao/invalido
             (tipo-do-erro #(catalogo/executar! (deps) :secretaria (ator ente "secretario") "situacao_da_materia"
                                                {:tipo "projeto_lei" :ano 2026})))))
    (testing "papel que a ferramenta nao atende e' negado"
      (is (= :autorizacao/negado
             (tipo-do-erro #(catalogo/executar! (deps) :secretaria (ator ente "cidadao") "situacao_da_materia"
                                                pelo-numero)))))))

(deftest tramitacao-da-materia
  (let [ente (random-uuid)
        p (proposicao! ente "Dispoe sobre iluminacao publica.")
        r (catalogo/executar! (deps) :secretaria (ator ente "secretario") "tramitacao_da_materia"
                              {:proposicao-id (str (:id p))})]
    (is (= (str (:id p)) (:proposicao-id r)))
    (is (vector? (:historico r)))))

(deftest pauta-da-sessao-da-vez
  (let [ente (random-uuid)
        depois (sessao! ente "2026-12-10T13:00:00Z")
        antes (sessao! ente "2026-12-03T13:00:00Z")
        vereador (ator ente "vereador")]
    (testing "sem sessao informada, a proxima agendada"
      (is (= (str antes) (:sessao-id (catalogo/executar! (deps) :vereador vereador "pauta_da_sessao" {})))))
    (testing "com sessao informada, aquela"
      (is (= (str depois) (:sessao-id (catalogo/executar! (deps) :vereador vereador "pauta_da_sessao"
                                                    {:sessao-id (str depois)})))))
    (testing "Casa sem sessao nenhuma: nada a mostrar"
      (is (nil? (catalogo/executar! (deps) :vereador (ator (random-uuid) "vereador") "pauta_da_sessao" {}))))))

(deftest ferramenta-fora-do-conjunto-nao-existe
  (is (= :validacao/ferramenta-desconhecida
         (tipo-do-erro #(catalogo/executar! (deps) :secretaria (ator (random-uuid) "secretario") "apagar_tudo" {}))))
  (is (= :validacao/ferramenta-desconhecida
         (tipo-do-erro #(catalogo/executar! (deps) :cidadao (ator (random-uuid) "secretario") "pauta_da_sessao" {})))))
