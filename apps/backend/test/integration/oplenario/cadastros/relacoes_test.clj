(ns oplenario.cadastros.relacoes-test
  "INTEGRACAO (PG real): as funcoes de relacao do cadastros (§22.5 eixo B). Prova a TEMPORALIDADE
  (disc.5: a mesma pergunta tem respostas diferentes em datas diferentes), a resolucao do tribunal
  competente (E1: override de municipio > default da UF) e o isolamento por tenant (RLS)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.db.comissao :as comissao]
            [oplenario.cadastros.db.estrutura :as estrutura]
            [oplenario.cadastros.db.referencia :as referencia]
            [oplenario.cadastros.db.vereador :as vereador]
            [oplenario.cadastros.relacoes.cadastro :as rel]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao])
  (:import (java.time LocalDate)))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- d [s] (LocalDate/parse s))

(defn- seed-referencia! []
  (referencia/inserir-municipio! *ds* {:codigo-ibge "2304400" :nome "Fortaleza" :uf "CE" :capital true :populacao 2703391})
  (referencia/inserir-tribunal! *ds* {:codigo "TCE-CE" :nome "TCE-CE" :uf "CE" :tipo "estadual"})
  (jdbc/execute! *ds* ["DELETE FROM cadastros.jurisdicao_camara WHERE uf = 'CE'"])
  (referencia/inserir-jurisdicao! *ds* {:id (random-uuid) :uf "CE" :municipio-ibge nil :tribunal-codigo "TCE-CE"}))

(deftest relacoes-temporais-e-tribunal
  (let [ente (random-uuid)
        ident (random-uuid)
        leg (random-uuid) ver (random-uuid) man (random-uuid) mesa (random-uuid)]
    (seed-referencia!)
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (estrutura/inserir-ente! tx {:ente-id ente :municipio-ibge "2304400" :nome-oficial "CM Fortaleza"})
        (estrutura/inserir-legislatura! tx {:id leg :ente-id ente :numero 19 :ano-inicio 2025 :ano-fim 2028 :vigente true})
        (vereador/inserir! tx {:id ver :ente-id ente :identidade-id ident :nome "Ana"})
        ;; mandato vigente cobrindo 2025; encerra em 2025-12-31 (teste temporal sem mudar estado).
        (vereador/inserir-mandato! tx {:id man :ente-id ente :vereador-id ver :legislatura-id leg
                                       :estado "vigente" :vigencia-inicio (d "2025-01-01") :vigencia-fim (d "2025-12-31")})
        (comissao/inserir! tx {:id mesa :ente-id ente :nome "Mesa" :tipo "mesa" :legislatura-id leg
                               :vigencia-inicio (d "2025-01-01") :vigencia-fim (d "2026-12-31")})
        (comissao/inserir-cargo! tx {:id (random-uuid) :ente-id ente :comissao-id mesa :vereador-id ver
                                     :cargo "presidente" :vigencia-inicio (d "2025-01-01") :vigencia-fim (d "2026-12-31")})))
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        ;; TEMPORALIDADE do mandato
        (is (true? (rel/tem-mandato-vigente? tx ident (d "2025-06-01"))) "mandato vigente em 2025")
        (is (false? (rel/tem-mandato-vigente? tx ident (d "2026-06-01"))) "mesmo mandato NAO vigente em 2026 (apos vigencia_fim)")
        (is (false? (rel/tem-mandato-vigente? tx ident (d "2024-06-01"))) "nem antes do inicio")
        ;; presidencia da Mesa (cargo ate 2026-12-31)
        (is (true? (rel/presidente-da-mesa? tx ident (d "2025-06-01"))) "presidente da Mesa em 2025")
        (is (false? (rel/presidente-da-mesa? tx ident (d "2027-06-01"))) "NAO presidente apos a vigencia do cargo")
        (is (= ident (rel/quem-exerce-presidencia tx (d "2025-06-01"))) "quem exerce a presidencia = a identidade")
        (is (nil? (rel/quem-exerce-presidencia tx (d "2027-06-01"))) "ninguem exerce apos a vigencia")
        ;; agregadores
        (is (= 1 (rel/membros-da-casa tx (d "2025-06-01"))) "1 membro da casa em 2025")
        (is (= 0 (rel/membros-da-casa tx (d "2026-06-01"))) "0 membros em 2026 (mandato encerrou)")
        (is (= 2703391 (rel/populacao tx)) "populacao do municipio do ente")
        ;; E1: tribunal competente (default da UF CE -> TCE-CE)
        (is (= "TCE-CE" (rel/tribunal-competente tx)) "tribunal competente resolvido por jurisdicao")))))

(deftest tribunal-override-de-municipio-vence-default-da-uf
  (let [ente (random-uuid)]
    (referencia/inserir-municipio! *ds* {:codigo-ibge "3550308" :nome "Sao Paulo" :uf "SP" :capital true :populacao 12300000})
    (referencia/inserir-tribunal! *ds* {:codigo "TCE-SP" :nome "TCE-SP" :uf "SP" :tipo "estadual"})
    (referencia/inserir-tribunal! *ds* {:codigo "TCM-SP" :nome "TCM Sao Paulo" :uf "SP" :tipo "tcm_municipio"})
    (jdbc/execute! *ds* ["DELETE FROM cadastros.jurisdicao_camara WHERE uf = 'SP'"])
    (referencia/inserir-jurisdicao! *ds* {:id (random-uuid) :uf "SP" :municipio-ibge nil :tribunal-codigo "TCE-SP"})
    (referencia/inserir-jurisdicao! *ds* {:id (random-uuid) :uf "SP" :municipio-ibge "3550308" :tribunal-codigo "TCM-SP"})
    (tenancy/com-tenant* *ds* ente
      (fn [tx] (estrutura/inserir-ente! tx {:ente-id ente :municipio-ibge "3550308" :nome-oficial "CM Sao Paulo"})))
    (is (= "TCM-SP" (tenancy/com-tenant* *ds* ente (fn [tx] (rel/tribunal-competente tx))))
        "override do municipio (TCM-SP) vence o default da UF (TCE-SP)")))

(deftest relacoes-isolam-por-tenant
  (let [a (random-uuid) b (random-uuid) ident (random-uuid) leg (random-uuid) ver (random-uuid)]
    (tenancy/com-tenant* *ds* a
      (fn [tx]
        (estrutura/inserir-legislatura! tx {:id leg :ente-id a :numero 1 :ano-inicio 2025 :ano-fim 2028 :vigente true})
        (vereador/inserir! tx {:id ver :ente-id a :identidade-id ident :nome "X"})
        (vereador/inserir-mandato! tx {:id (random-uuid) :ente-id a :vereador-id ver :legislatura-id leg
                                       :estado "vigente" :vigencia-inicio (d "2025-01-01")})))
    (is (true? (tenancy/com-tenant* *ds* a (fn [tx] (rel/tem-mandato-vigente? tx ident (d "2025-06-01"))))) "ente A ve o mandato")
    (is (false? (tenancy/com-tenant* *ds* b (fn [tx] (rel/tem-mandato-vigente? tx ident (d "2025-06-01"))))) "ente B NAO ve o mandato de A (RLS)")
    (is (= 0 (tenancy/com-tenant* *ds* b (fn [tx] (rel/membros-da-casa tx (d "2025-06-01"))))) "ente B conta 0 membros")))

(deftest mesa-sobreposta-e-rejeitada-anti-fail-open
  ;; review F1.2 #1: duas Mesas EFETIVADAS com vigencia sobreposta no mesmo ente fariam a heuristica
  ;; de mesa-vigente-id escolher a errada -> autorizacao indevida. O EXCLUDE constraint barra na origem.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (comissao/inserir! tx {:id (random-uuid) :ente-id ente :nome "Mesa 25-26" :tipo "mesa"
                               :vigencia-inicio (d "2025-01-01") :vigencia-fim (d "2026-12-31")})))
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx]
                     (comissao/inserir! tx {:id (random-uuid) :ente-id ente :nome "Mesa sobreposta" :tipo "mesa"
                                            :vigencia-inicio (d "2026-06-01") :vigencia-fim (d "2027-12-31")}))))
        "uma 2a Mesa com vigencia sobreposta e' rejeitada (uq_uma_mesa_ativa)")
    ;; comissao NAO-mesa sobreposta e' permitida (o constraint e' so p/ tipo='mesa').
    (is (some? (tenancy/com-tenant* *ds* ente
                 (fn [tx] (comissao/inserir! tx {:id (random-uuid) :ente-id ente :nome "CCJ" :tipo "permanente"
                                                 :vigencia-inicio (d "2025-06-01") :vigencia-fim (d "2027-01-01")}))))
        "comissao nao-mesa sobreposta e' permitida")))

(deftest registro-expoe-as-nove-relacoes
  (is (= #{"tem_mandato_vigente" "é_membro_de_comissao" "é_presidente_de_comissao" "é_presidente_da_mesa"
           "é_secretario_da_mesa" "quem_exerce_presidencia" "populacao" "membros_da_casa" "tribunal_competente"}
         (set (keys rel/relacoes)))
      "o registro expoe as 9 relacoes do cadastros p/ a injecao da F2"))
