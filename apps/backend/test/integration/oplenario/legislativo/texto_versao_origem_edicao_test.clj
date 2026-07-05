(ns oplenario.legislativo.texto-versao-origem-edicao-test
  "Onda B Slice 2 — prova que a migration 20260620000054 aceita origem_versao='edicao' (o CHECK antigo
  lancaria)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.db.proposicao :as prop]
            [oplenario.legislativo.db.texto-versao :as texto]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(deftest nova-versao-com-origem-edicao-aceita
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [p (prop/protocolar! tx {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                                       :uf "CE" :municipio-nome "Fortaleza" :ementa "Materia de teste"})
              r (texto/nova-versao! tx {:id (random-uuid) :ente-id ente :proposicao-id (:id p)
                                        :origem-versao "edicao" :formato "markdown"
                                        :texto-inline "## Art. 1o Teste."})]
          (is (= 1 (:numero-versao r))))))))
