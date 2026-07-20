(ns oplenario.cadastros.repositorio-identidade-test
  "INTEGRACAO (PG real) — achado 3 da revisao de `onda-e-inbox-notificacoes` (task-5): `identidade-do-vereador-em-tx`
  (`components/repositorio.clj`) e' a UNICA fn que atravessa a fronteira §22.10 injetada pelo host em
  `legislativo` (Onda E fatia 1, `sistema.clj`) — e ate' aqui so' era exercitada por FAKES nos testes de
  integracao de `legislativo` (`resolver-fixo`/`resolver-coringa`). Prova a leitura REAL em
  `cadastros.vereador`: se a chave devolvida por `vereador/buscar` mudasse (ex.: `:identidade_id` namespaced
  em vez de `:identidade-id` kebab), a fn devolveria `nil` SEMPRE — nenhum vereador receberia notificacao em
  producao — e a suite inteira de `legislativo` continuaria verde, porque nenhum teste la' chama esta fn de
  verdade."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.cadastros.components.repositorio :as repo]
            [oplenario.cadastros.db.estrutura :as estrutura]
            [oplenario.cadastros.db.referencia :as referencia]
            [oplenario.cadastros.db.vereador :as vereador]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

;; reference data (sem ente_id) e' semeada como DONO (bypassa RLS) — mesmo padrao de db_test/seed-municipio!.
(defn- seed-municipio! []
  (referencia/inserir-municipio! *ds* {:codigo-ibge "2304400" :nome "Fortaleza" :uf "CE" :capital true :populacao 1}))

(deftest identidade-do-vereador-em-tx-devolve-a-identidade-vinculada
  (let [ente (random-uuid) ver (random-uuid) identidade (random-uuid)]
    (seed-municipio!)
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (estrutura/inserir-ente! tx {:ente-id ente :municipio-ibge "2304400" :nome-oficial "Camara Identidade"})
        (vereador/inserir! tx {:id ver :ente-id ente :identidade-id identidade :nome "Ana"})
        (is (= identidade (repo/identidade-do-vereador-em-tx tx ente ver))
            "vereador COM identidade vinculada -> devolve o id (o elo real que os fakes de legislativo nunca exercitam)")))))

(deftest identidade-do-vereador-em-tx-devolve-nil-sem-vinculo
  (let [ente (random-uuid) ver (random-uuid)]
    (seed-municipio!)
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (estrutura/inserir-ente! tx {:ente-id ente :municipio-ibge "2304400" :nome-oficial "Camara Identidade"})
        (vereador/inserir! tx {:id ver :ente-id ente :nome "Bruno"})  ; SEM identidade-id
        (is (nil? (repo/identidade-do-vereador-em-tx tx ente ver))
            "vereador SEM identidade vinculada -> nil (o consumer trata como 'nao notifica', nunca como erro)")))))

(deftest identidade-do-vereador-em-tx-devolve-nil-para-vereador-inexistente
  (let [ente (random-uuid)]
    (seed-municipio!)
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (estrutura/inserir-ente! tx {:ente-id ente :municipio-ibge "2304400" :nome-oficial "Camara Identidade"})
        (is (nil? (repo/identidade-do-vereador-em-tx tx ente (random-uuid)))
            "vereador-id que nao existe neste ente -> nil, nunca excecao")))))
