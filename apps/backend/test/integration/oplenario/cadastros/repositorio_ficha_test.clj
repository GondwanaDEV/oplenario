(ns oplenario.cadastros.repositorio-ficha-test
  "INTEGRACAO (PG real) — RepoCadastros/listar-vereadores + ficha-vereador (Task 2): leitura composta
  NUMA UNICA tx (mesma disciplina de ficha-completa-da-proposicao do legislativo)."
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

(deftest ficha-vereador-nil-quando-nao-existe
  (is (nil? (repo/ficha-vereador *repo* (random-uuid) (random-uuid) (LocalDate/of 2026 7 14)))))

(deftest ficha-vereador-compoe-vereador-mandato-legislatura-e-comissoes
  (let [ente (random-uuid)
        leg-id (random-uuid)
        ver-id (random-uuid)
        mandato-id (random-uuid)
        mesa-id (random-uuid)
        hoje (LocalDate/of 2026 7 14)
        ini  (LocalDate/of 2025 1 1)]
    (repo/criar-legislatura! *repo* ente
      {:id leg-id :ente-id ente :numero 20 :ano-inicio 2025 :ano-fim 2028 :vigente true})
    (repo/criar-vereador! *repo* ente {:id ver-id :ente-id ente :nome "Elisa" :nome-parlamentar "Elisa Vereadora"})
    (repo/criar-mandato! *repo* ente
      {:id mandato-id :ente-id ente :vereador-id ver-id :legislatura-id leg-id :partido "PDT"
       :estado "vigente" :natureza "titular" :vigencia-inicio ini :vigencia-fim nil})
    (repo/criar-comissao! *repo* ente {:id mesa-id :ente-id ente :nome "Mesa Diretora" :tipo "mesa"
                                       :legislatura-id leg-id :vigencia-inicio ini})
    (repo/criar-membro! *repo* ente {:id (random-uuid) :ente-id ente :comissao-id mesa-id
                                     :vereador-id ver-id :vigencia-inicio ini})
    (repo/criar-cargo! *repo* ente {:id (random-uuid) :ente-id ente :comissao-id mesa-id
                                    :vereador-id ver-id :cargo "presidente" :vigencia-inicio ini})
    (let [f (repo/ficha-vereador *repo* ente ver-id hoje)]
      (is (= "Elisa Vereadora" (:nome-parlamentar (:vereador f))))
      (is (= "PDT" (:partido (:mandato f))))
      (is (= 20 (:numero (:legislatura f))))
      (is (= 1 (count (:comissoes f))))
      (is (= "presidente" (:cargo (first (:comissoes f))))))
    ;; ente-scope: outro ente NAO ve a ficha (RLS) -> nil, sem vazar via cross-tenant.
    (is (nil? (repo/ficha-vereador *repo* (random-uuid) ver-id hoje)))))

(deftest listar-vereadores-delega-ao-db
  (let [ente (random-uuid)
        ver-id (random-uuid)
        hoje (LocalDate/of 2026 7 14)]
    (repo/criar-vereador! *repo* ente {:id ver-id :ente-id ente :nome "Fabio"})
    (let [rows (repo/listar-vereadores *repo* ente hoje)]
      (is (= ["Fabio"] (map :nome rows))))))
