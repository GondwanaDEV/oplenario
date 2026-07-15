(ns oplenario.cadastros.repositorio-escrita-test
  "INTEGRACAO (PG real) — Slice 4: os metodos de ESCRITA do RepoCadastros, cada um numa tx do tenant. Prova
  a tx da licenca (linha gravada + estado do mandato vira 'licenciado' atomicamente), o guard de sobreposicao
  (409), os sinais de 404 (nil) e o isolamento RLS (escrita de um tenant nao atinge outro)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.components.repositorio :as repo]
            [oplenario.cadastros.db.estrutura :as estrutura]
            [oplenario.cadastros.db.referencia :as referencia]
            [oplenario.cadastros.db.vereador :as vereador]
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

;; reference data (sem ente_id) e' semeada como DONO (bypassa RLS) — mesmo padrao de db_test/seed-municipio!.
(defn- seed-municipio! []
  (referencia/inserir-municipio! (:ds (:datasource *repo*))
    {:codigo-ibge "2304400" :nome "Fortaleza" :uf "CE" :capital true :populacao 1}))

(defn- semear-ente-leg-vereador!
  "Ente + legislatura + vereador NUMA tx do tenant — a base comum dos 4 deftests."
  [ente leg ver]
  (repo/transacao *repo* ente
    (fn [tx]
      (estrutura/inserir-ente! tx {:ente-id ente :municipio-ibge "2304400" :nome-oficial "Camara Escrita"})
      (estrutura/inserir-legislatura! tx {:id leg :ente-id ente :numero 20 :ano-inicio 2025 :ano-fim 2028 :vigente true})
      (vereador/inserir! tx {:id ver :ente-id ente :nome "Ana"}))))

(deftest registrar-mandato-cria-e-recusa-sobreposto
  (let [ente (random-uuid) leg (random-uuid) ver (random-uuid)
        ini (LocalDate/parse "2025-01-01")]
    (seed-municipio!) (semear-ente-leg-vereador! ente leg ver)
    (let [m1-id (random-uuid)
          r1 (repo/registrar-mandato! *repo* ente
               {:id m1-id :ente-id ente :vereador-id ver :legislatura-id leg :partido "PT"
                :estado "vigente" :natureza "titular" :vigencia-inicio ini :vigencia-fim nil})]
      (is (= {:id m1-id} r1) "1o mandato registra normalmente"))
    (let [ex (try
               (repo/registrar-mandato! *repo* ente
                 {:id (random-uuid) :ente-id ente :vereador-id ver :legislatura-id leg :partido "PSDB"
                  :estado "vigente" :natureza "titular" :vigencia-inicio ini :vigencia-fim nil})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "2o mandato 'vigente' sobreposto do MESMO vereador lanca excecao")
      (is (= :conflito/mandato-sobreposto (:tipo (ex-data ex)))))
    (is (nil? (repo/registrar-mandato! *repo* ente
                {:id (random-uuid) :ente-id ente :vereador-id (random-uuid) :legislatura-id leg
                 :estado "vigente" :vigencia-inicio (LocalDate/parse "2026-01-01")}))
        "vereador-id desconhecido -> nil (404)")
    (is (nil? (repo/registrar-mandato! *repo* ente
                {:id (random-uuid) :ente-id ente :vereador-id ver :legislatura-id (random-uuid)
                 :estado "vigente" :vigencia-inicio (LocalDate/parse "2026-01-01")}))
        "legislatura-id desconhecida -> nil (404)")))

(deftest registrar-licenca-flipa-estado-atomicamente
  (let [ente (random-uuid) leg (random-uuid) ver (random-uuid) mandato-id (random-uuid)
        ini (LocalDate/parse "2025-01-01") hoje (LocalDate/parse "2026-07-14")]
    (seed-municipio!) (semear-ente-leg-vereador! ente leg ver)
    (repo/transacao *repo* ente
      (fn [tx]
        (vereador/inserir-mandato! tx {:id mandato-id :ente-id ente :vereador-id ver :legislatura-id leg
                                       :partido "PT" :estado "vigente" :natureza "titular"
                                       :vigencia-inicio ini :vigencia-fim nil})))
    (let [lic-id (random-uuid)
          r (repo/registrar-licenca! *repo* ente ver
              {:id lic-id :ente-id ente :inicio hoje :motivo "saude"} hoje)]
      (is (= {:id lic-id} r) "licenca registrada")
      ;; le de volta NUMA (outra) tx do tenant: a tx da licenca flipou o mandato atomicamente.
      (repo/transacao *repo* ente
        (fn [tx]
          (let [mandato (first (filter #(= mandato-id (:id %)) (vereador/mandatos-do-vereador tx ente ver)))
                licenca (jdbc/execute-one! tx ["SELECT id FROM cadastros.mandato_licenca WHERE id = ?" lic-id])]
            (is (= "licenciado" (:estado mandato)) "estado do mandato vira 'licenciado' na MESMA tx da licenca")
            (is (some? licenca) "linha mandato_licenca foi gravada")))))
    (let [ver-sem-mandato (random-uuid)
          ex (do (repo/transacao *repo* ente (fn [tx] (vereador/inserir! tx {:id ver-sem-mandato :ente-id ente :nome "Bruno"})))
                 (try (repo/registrar-licenca! *repo* ente ver-sem-mandato {:id (random-uuid) :ente-id ente :inicio hoje :motivo "x"} hoje)
                      nil
                      (catch clojure.lang.ExceptionInfo e e)))]
      (is (some? ex) "vereador sem mandato vigente lanca excecao")
      (is (= :conflito/sem-mandato-vigente (:tipo (ex-data ex)))))
    (is (nil? (repo/registrar-licenca! *repo* ente (random-uuid) {:id (random-uuid) :ente-id ente :inicio hoje :motivo "x"} hoje))
        "vereador-id desconhecido -> nil (404)")))

(deftest atualizar-vereador-conta-linhas
  (let [ente (random-uuid) ver (random-uuid)]
    (seed-municipio!)
    (repo/transacao *repo* ente
      (fn [tx]
        (estrutura/inserir-ente! tx {:ente-id ente :municipio-ibge "2304400" :nome-oficial "Camara Escrita"})
        (vereador/inserir! tx {:id ver :ente-id ente :nome "Carla"})))
    (is (= 1 (repo/atualizar-vereador! *repo* ente ver {:nome "Carla Nova"})) "linha existente -> update-count 1")
    (is (= 0 (repo/atualizar-vereador! *repo* ente (random-uuid) {:nome "X"})) "id desconhecido -> update-count 0")))

(deftest ligar-identidade-recusa-mesma-identidade-em-dois-vereadores-da-casa
  ;; Review Task 9 IMPORTANT-3: o unico teste de 409 pre-existente (`ligar-identidade-conflito-409` em
  ;; vereador-http-in-test) usa um Repo FAKE que joga o ex-info pronto — prova so' que o diplomat converte
  ;; ex-info -> 409 (padrao ja' provado antes desta task). Este teste roda contra Postgres REAL e exercita
  ;; o caminho novo de fato: `(catch PSQLException e (.getSQLState e) "23505")` em components/repositorio.clj
  ;; + o indice UNIQUE parcial `idx_vereador_identidade_unica` (migration ...0060) que o sustenta.
  (let [ente (random-uuid) ver-a (random-uuid) ver-b (random-uuid) ident (random-uuid)]
    (seed-municipio!)
    (repo/transacao *repo* ente
      (fn [tx]
        (estrutura/inserir-ente! tx {:ente-id ente :municipio-ibge "2304400" :nome-oficial "Camara Escrita"})
        (vereador/inserir! tx {:id ver-a :ente-id ente :nome "Ana"})
        (vereador/inserir! tx {:id ver-b :ente-id ente :nome "Bia"})))
    (is (= 1 (repo/ligar-identidade! *repo* ente ver-a ident)) "1a ligacao: update-count 1, sem colisao")
    (let [ex (try (repo/ligar-identidade! *repo* ente ver-b ident)
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "2o vereador da MESMA Casa tentando a MESMA identidade lanca excecao (23505 real)")
      (is (= :conflito/identidade-ja-vinculada (:tipo (ex-data ex)))))
    (is (= 0 (repo/ligar-identidade! *repo* ente (random-uuid) (random-uuid)))
        "vereador-id desconhecido -> update-count 0 (404), nao colisao")))

(deftest escrita-isola-cross-tenant
  (let [ente-a (random-uuid) ente-b (random-uuid) leg (random-uuid) ver (random-uuid)
        ini (LocalDate/parse "2025-01-01")]
    (seed-municipio!) (semear-ente-leg-vereador! ente-a leg ver)
    (repo/registrar-mandato! *repo* ente-a
      {:id (random-uuid) :ente-id ente-a :vereador-id ver :legislatura-id leg :partido "PT"
       :estado "vigente" :natureza "titular" :vigencia-inicio ini :vigencia-fim nil})
    (is (= [] (repo/transacao *repo* ente-b (fn [tx] (vereador/mandatos-do-vereador tx ente-b ver))))
        "escrita sob ente A nao aparece sob a tx de ente B (RLS)")
    (is (nil? (repo/registrar-mandato! *repo* ente-b
                {:id (random-uuid) :ente-id ente-b :vereador-id ver :legislatura-id leg :partido "PT"
                 :estado "vigente" :vigencia-inicio ini}))
        "vereador de A e' invisivel sob a tx de B -> 404 (nil), nunca vazamento cross-tenant")))
