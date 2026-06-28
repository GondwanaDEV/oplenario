(ns oplenario.cadastros.db-test
  "INTEGRACAO (PG real): o db/ do cadastros sob a tx do tenant. Prova round-trip (incl. datas LocalDate e
  carimbos Instant via kernel/db-tipos), conformidade com os models internos, isolamento cross-tenant na
  RLS das tabelas reais, WITH CHECK no insert cross-tenant, e a semantica COALESCE-unique da jurisdicao (E1)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.db.comissao :as comissao]
            [oplenario.cadastros.db.estrutura :as estrutura]
            [oplenario.cadastros.db.referencia :as referencia]
            [oplenario.cadastros.db.vereador :as vereador]
            [oplenario.cadastros.models.cadastro :as mod]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao])
  (:import (java.time Instant LocalDate)))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

;; reference data (sem ente_id) e' semeada como DONO (bypassa RLS); o app so a LE.
(defn- seed-municipio! [] (referencia/inserir-municipio! *ds* {:codigo-ibge "2304400" :nome "Fortaleza" :uf "CE" :capital true :populacao 2703391}))

(deftest cadastro-round-trip-e-conformidade-de-modelo
  (let [ente (random-uuid)
        leg  (random-uuid)
        ver  (random-uuid)
        man  (random-uuid)
        mesa (random-uuid)
        ini  (LocalDate/parse "2025-01-01")]
    (seed-municipio!)
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (estrutura/inserir-ente! tx {:ente-id ente :municipio-ibge "2304400" :nome-oficial "Camara Municipal de Fortaleza" :nome-curto "CMFor"})
        (estrutura/inserir-legislatura! tx {:id leg :ente-id ente :numero 19 :ano-inicio 2025 :ano-fim 2028 :vigente true})
        (vereador/inserir! tx {:id ver :ente-id ente :nome "Maria Souza" :nome-parlamentar "Maria do Povo"})
        (vereador/inserir-mandato! tx {:id man :ente-id ente :vereador-id ver :legislatura-id leg :partido "PT"
                                       :estado "vigente" :natureza "titular" :vigencia-inicio ini})
        (comissao/inserir! tx {:id mesa :ente-id ente :nome "Mesa Diretora" :tipo "mesa" :legislatura-id leg :vigencia-inicio ini})
        (comissao/inserir-cargo! tx {:id (random-uuid) :ente-id ente :comissao-id mesa :vereador-id ver :cargo "presidente" :vigencia-inicio ini})
        (comissao/inserir-membro! tx {:id (random-uuid) :ente-id ente :comissao-id mesa :vereador-id ver :vigencia-inicio ini})))
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [e (estrutura/buscar-ente tx)
              v (vereador/buscar tx ver)
              ms (vereador/mandatos-do-vereador tx ente ver)
              mz (comissao/mesa-vigente tx ini)]
          (is (= "Camara Municipal de Fortaleza" (:nome-oficial e)) "round-trip do ente")
          (is (m/validate mod/Ente (select-keys e [:ente-id :municipio-ibge :nome-oficial :nome-curto :brasao-ref]))
              "ente bate o model interno")
          (is (= "Maria do Povo" (:nome-parlamentar v)) "round-trip do vereador")
          (is (= 1 (count ms)) "um mandato")
          (is (instance? LocalDate (:vigencia-inicio (first ms))) "date volta como LocalDate (db-tipos)")
          (is (m/validate mod/Mandato (select-keys (first ms) [:ente-id :id :vereador-id :legislatura-id :partido
                                                               :estado :natureza :vigencia-inicio :vigencia-fim :fim-efetivo]))
              "mandato bate o model interno")
          (is (= "mesa" (:tipo mz)) "a Mesa vigente e' tipo='mesa'"))))))

(deftest rls-isola-cadastro-cross-tenant
  (let [a (random-uuid) b (random-uuid) va (random-uuid)]
    (tenancy/com-tenant* *ds* a (fn [tx] (vereador/inserir! tx {:id va :ente-id a :nome "Vereador de A"})))
    (is (some? (tenancy/com-tenant* *ds* a (fn [tx] (vereador/buscar tx va)))) "ente A ve o proprio vereador")
    (is (nil? (tenancy/com-tenant* *ds* b (fn [tx] (vereador/buscar tx va)))) "ente B NAO ve o vereador de A (RLS)")))

(deftest with-check-bloqueia-vereador-cross-tenant
  (let [a (random-uuid) b (random-uuid)]
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* a (fn [tx] (vereador/inserir! tx {:id (random-uuid) :ente-id b :nome "intruso"}))))
        "sessao do ente A NAO insere vereador com ente_id=B (WITH CHECK)")))

(deftest jurisdicao-coalesce-permite-default-e-override-bloqueia-duplicata
  (jdbc/execute! *ds* ["TRUNCATE cadastros.jurisdicao_camara"])  ; reference table sem ente_id; insert autocommit
  (referencia/inserir-municipio! *ds* {:codigo-ibge "3550308" :nome "Sao Paulo" :uf "SP" :capital true})
  (referencia/inserir-tribunal! *ds* {:codigo "TCE-SP" :nome "TCE-SP" :uf "SP" :tipo "estadual"})
  (referencia/inserir-tribunal! *ds* {:codigo "TCM-SP" :nome "TCM Sao Paulo" :uf "SP" :tipo "tcm_municipio"})
  ;; default da UF (municipio NULL) + override da capital coexistem (COALESCE unique).
  (referencia/inserir-jurisdicao! *ds* {:id (random-uuid) :uf "SP" :municipio-ibge nil :tribunal-codigo "TCE-SP"})
  (referencia/inserir-jurisdicao! *ds* {:id (random-uuid) :uf "SP" :municipio-ibge "3550308" :tribunal-codigo "TCM-SP"})
  (is (thrown? Exception
               (referencia/inserir-jurisdicao! *ds* {:id (random-uuid) :uf "SP" :municipio-ibge nil :tribunal-codigo "TCE-SP"}))
      "dois defaults da MESMA UF colidem (COALESCE(municipio,'*') = unico por UF)"))

(deftest carimbo-criado-em-volta-como-instant
  (let [ente (random-uuid)]
    (seed-municipio!)
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (estrutura/inserir-ente! tx {:ente-id ente :municipio-ibge "2304400" :nome-oficial "X"})
        (let [linha (jdbc/execute-one! tx ["SELECT criado_em FROM cadastros.ente"])]
          (is (instance? Instant (:ente/criado_em linha)) "timestamptz volta como Instant (db-tipos)"))))))
