(ns oplenario.cadastros.estrutura-test
  "Onda B Slice 2 — uf-e-municipio: o FATO que legislativo/protocolar! precisa p/ a URN (eixo H), via join
  DENTRO do schema cadastros (municipios+ente), sem cross-schema (§22.10)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.cadastros.db.estrutura :as estrutura]
            [oplenario.cadastros.db.referencia :as referencia]
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

;; reference data (sem ente_id) NAO e' seedada por migration ("Seed por carga" — carga externa futura);
;; semeada aqui como DONO (via *ds*, bypassa o GRANT SELECT-only de oplenario_app), igual ao padrao ja'
;; usado em db_test.clj/relacoes_test.clj deste modulo.
(defn- seed-municipio! []
  (referencia/inserir-municipio! *ds* {:codigo-ibge "2304400" :nome "Fortaleza" :uf "CE" :capital true :populacao 2703391}))

(deftest uf-e-municipio-resolve-do-ente-corrente
  (let [ente (random-uuid)]
    (seed-municipio!)
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (estrutura/inserir-ente! tx {:ente-id ente :municipio-ibge "2304400" :nome-oficial "Camara de Teste"})
        (is (= {:uf "CE" :municipio-nome "Fortaleza"} (estrutura/uf-e-municipio tx)))))))

(deftest uf-e-municipio-nil-quando-ente-sem-perfil
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (is (nil? (estrutura/uf-e-municipio tx)))))))
