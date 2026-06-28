(ns oplenario.repo-test
  "INTEGRACAO (PG real): os Repo-Components (ADR-0001 §3 revisao) — o banco DISPONIBILIZADO como Stuart
  Sierra Component. Prova: o sistema inicia com os repos recebendo :datasource via `using`; as ACOES
  sao tenant-aware (com-tenant* por dentro); isolam por tenant; `transacao` compoe atomico; supratenant
  (identidade) roda sobre o :ds. O controller/teste depende do Component, nunca do db/ direto."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.cadastros.components.repositorio :as rc]
            [oplenario.cadastros.db.estrutura :as cad-estrutura]
            [oplenario.cadastros.db.referencia :as ref]
            [oplenario.cadastros.db.vereador :as cad-vereador]
            [oplenario.config :as config]
            [oplenario.identidade.components.repositorio :as ri]
            [oplenario.migracao :as migracao]
            [oplenario.sistema :as sistema])
  (:import (java.time LocalDate)))

(def ^:dynamic *sys* nil)

(use-fixtures :once
  (fn [t]
    (let [s (component/start (sistema/novo-sistema (config/carregar)))]
      (migracao/migrar! (:ds (:datasource s)))
      ;; reference data (municipios) e' seed de admin/owner — sobre o :ds, fora do repo do app.
      (ref/inserir-municipio! (:ds (:datasource s)) {:codigo-ibge "2304400" :nome "Fortaleza" :uf "CE" :capital true :populacao 2703391})
      (binding [*sys* s] (try (t) (finally (component/stop s)))))))

(defn- dv [ds] (let [r (mod (reduce + (map * ds (range (inc (count ds)) 1 -1))) 11)] (if (< r 2) 0 (- 11 r))))
(defn- cpf-valido [] (let [b (vec (repeatedly 9 #(rand-int 10))) d1 (dv b)] (apply str (concat b [d1 (dv (conj b d1))]))))

(deftest repo-cadastros-disponibiliza-acoes-tenant-aware
  (let [repo (:repo-cadastros *sys*)
        a (random-uuid) b (random-uuid) ver (random-uuid) leg (random-uuid) ini (LocalDate/parse "2025-01-01")]
    ;; ACOES via o Component (nunca db/ direto); cada uma trata com-tenant* por dentro
    (rc/criar-ente! repo a {:ente-id a :municipio-ibge "2304400" :nome-oficial "CM Fortaleza"})
    (is (= "CM Fortaleza" (:nome-oficial (rc/buscar-ente repo a))) "acao buscar-ente via repo")
    ;; transacao: varias acoes numa UNICA tx do tenant
    (rc/transacao repo a
      (fn [tx]
        (cad-estrutura/inserir-legislatura! tx {:id leg :ente-id a :numero 19 :ano-inicio 2025 :ano-fim 2028 :vigente true})
        (cad-vereador/inserir! tx {:id ver :ente-id a :nome "Ana"})
        (cad-vereador/inserir-mandato! tx {:id (random-uuid) :ente-id a :vereador-id ver :legislatura-id leg :vigencia-inicio ini})))
    (is (some? (rc/buscar-vereador repo a ver)) "ente A ve o proprio vereador")
    (is (= 1 (count (rc/mandatos-do-vereador repo a ver))) "mandato criado na transacao")
    ;; ISOLAMENTO: o repo do mesmo Component nao vaza p/ outro tenant
    (is (nil? (rc/buscar-vereador repo b ver)) "ente B NAO ve o vereador de A (RLS via repo)")))

(deftest repo-identidade-supratenant-e-tenant
  (let [repo (:repo-identidade *sys*)
        ente (random-uuid) iid (random-uuid)]
    ;; supratenant (sobre o :ds): retorna o id canonico
    (is (= iid (ri/criar-identidade! repo {:id iid :cpf (cpf-valido) :nome "Cidada"})) "criar-identidade via repo")
    ;; tenant: vinculo + papel isolados por ente
    (ri/criar-vinculo! repo ente {:id (random-uuid) :ente-id ente :identidade-id iid :tipo "vereador"})
    (ri/adicionar-papel! repo ente {:id (random-uuid) :ente-id ente :identidade-id iid :papel "vereador"})
    (is (= ["vereador"] (mapv :tipo (ri/vinculos-de repo ente iid))) "vinculo via repo")
    (is (= #{"vereador"} (ri/papeis-de repo ente iid)) "papeis via repo")))
