(ns oplenario.legislativo.proposicao-editar-db-test
  "Onda B Slice 2 — db/proposicao.clj/editar!: PATCH parcial (CAS), guard de estado terminal, conflito de
  lock-version, inexistente."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.db.proposicao :as prop]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- protocolar! [tx ente]
  (prop/protocolar! tx {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                        :uf "CE" :municipio-nome "Fortaleza" :ementa "Ementa original"}))

(deftest editar-atualiza-so-os-campos-presentes
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{:keys [id]} (protocolar! tx ente)]
          (prop/editar! tx {:id id :ente-id ente :ementa "Ementa corrigida" :updated-by (random-uuid) :lock-version 0})
          (let [atual (prop/buscar tx ente id)]
            (is (= "Ementa corrigida" (:ementa atual)))
            (is (= 1 (:lock-version atual)))))))))

(deftest editar-conflito-de-lock-version-lanca
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{:keys [id]} (protocolar! tx ente)]
          (is (thrown? clojure.lang.ExceptionInfo
                       (prop/editar! tx {:id id :ente-id ente :ementa "X" :updated-by (random-uuid) :lock-version 99}))))))))

(deftest editar-inexistente-lanca
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (is (thrown? clojure.lang.ExceptionInfo
                     (prop/editar! tx {:id (random-uuid) :ente-id ente :ementa "X" :updated-by (random-uuid) :lock-version 0})))))))

(deftest editar-estado-terminal-lanca
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{:keys [id]} (protocolar! tx ente)]
          (prop/mudar-estado! tx {:id id :ente-id ente :estado "arquivada" :updated-by (random-uuid) :lock-version 0})
          (is (thrown? clojure.lang.ExceptionInfo
                       (prop/editar! tx {:id id :ente-id ente :ementa "X" :updated-by (random-uuid) :lock-version 1}))))))))
