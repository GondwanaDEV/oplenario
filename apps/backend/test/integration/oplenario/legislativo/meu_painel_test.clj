(ns oplenario.legislativo.meu-painel-test
  "INTEGRACAO (PG real) — borda `/meu` do vereador (Onda C1). Namespace estendido pelas proximas tasks
  (2/3/4: read do painel + ciencia + HTTP); esta task cobre SO a Task 1 — o host resolve
  identidade->vereador-id por inversao de dependencia (§22.5.3, exceção nomeada — mesma forma de
  `membros-da-casa`), sem o `legislativo` importar `cadastros`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.cadastros.components.repositorio :as repo-cadastros]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.migracao :as migracao]
            [oplenario.rotas :as rotas]))

(def ^:dynamic *repo-cadastros* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*repo-cadastros* (repo-cadastros/->RepoCadastrosPg c)]
        (try (t) (finally (component/stop c)))))))

(deftest resolver-vereador-resolve-identidade->vereador-id-no-ente
  (let [ente (random-uuid)
        identidade (random-uuid)
        vereador-id (random-uuid)]
    (repo-cadastros/criar-vereador! *repo-cadastros* ente
      {:id vereador-id :ente-id ente :identidade-id identidade :nome "Fulana" :nome-parlamentar "Fulana"})
    (is (= vereador-id (rotas/resolver-vereador *repo-cadastros* ente identidade)))))

(deftest resolver-vereador-devolve-nil-quando-identidade-sem-cadastro-neste-ente
  (let [ente (random-uuid)
        identidade-sem-cadastro (random-uuid)]
    (is (nil? (rotas/resolver-vereador *repo-cadastros* ente identidade-sem-cadastro)))))
