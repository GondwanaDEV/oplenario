(ns oplenario.cadastros.repositorio-membros-test
  "INTEGRACAO (PG real) — RepoCadastros/membros-da-casa (novo metodo, FE Onda A1): expoe
  cadastros.relacoes.cadastro/membros-da-casa via o Repo, p/ o host injetar em outros modulos."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.cadastros.components.repositorio :as repo]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.migracao :as migracao])
  (:import (java.time LocalDate)))

(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*repo* (repo/->RepoCadastrosPg c)]
        (try (t) (finally (component/stop c)))))))

(deftest membros-da-casa-conta-mandatos-vigentes
  (let [ente (random-uuid)
        leg-id (random-uuid)
        v1-id (random-uuid)
        v2-id (random-uuid)]
    (repo/criar-legislatura! *repo* ente
      {:id leg-id :ente-id ente :numero 1 :ano-inicio 2025 :ano-fim 2028 :vigente true})
    (repo/criar-vereador! *repo* ente {:id v1-id :ente-id ente :nome "A" :identidade-id nil})
    (repo/criar-vereador! *repo* ente {:id v2-id :ente-id ente :nome "B" :identidade-id nil})
    (repo/criar-mandato! *repo* ente {:id (random-uuid) :ente-id ente :vereador-id v1-id
                                      :legislatura-id leg-id :estado "vigente"
                                      :vigencia-inicio (LocalDate/of 2025 1 1) :vigencia-fim nil})
    (repo/criar-mandato! *repo* ente {:id (random-uuid) :ente-id ente :vereador-id v2-id
                                      :legislatura-id leg-id :estado "vigente"
                                      :vigencia-inicio (LocalDate/of 2025 1 1) :vigencia-fim nil})
    (is (= 2 (repo/membros-da-casa *repo* ente (LocalDate/of 2026 7 4))))
    (is (= 0 (repo/membros-da-casa *repo* (random-uuid) (LocalDate/of 2026 7 4))) "RLS: outro ente conta 0")))
