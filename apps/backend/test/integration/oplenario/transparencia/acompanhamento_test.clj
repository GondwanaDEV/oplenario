(ns oplenario.transparencia.acompanhamento-test
  "INTEGRACAO (PG real) — F6c Slice 2: ACOMPANHAMENTO do cidadao (§16.5). Prova, contra o banco sob FORCE RLS
  (mig 0045), via o Repo-Component + os controllers: (1) seguir materia existente -> subscricao 'ativa' +
  aparece em 'minhas'; (2) GUARD: seguir materia inexistente -> nil (borda 404); (3) re-seguir REATIVA a MESMA
  linha (UPSERT, sem duplicar); (4) deixar de seguir = soft-cancel idempotente (some de 'minhas'; 2a vez
  no-op); (5) isolamento de tenant (RLS). A materia e' inserida direto no read-model (o follow so' precisa que
  ela EXISTA — o fluxo de projecao ja' e' provado em portal_test)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.transparencia.components.repositorio :as repo]
            [oplenario.transparencia.controllers :as controllers]
            [oplenario.transparencia.db.materia :as db-materia]))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c) *repo* (repo/->RepoTransparenciaPg c)]
        (try (t) (finally (component/stop c)))))))

(defn- criar-materia!
  "Insere uma materia direto no read-model (o follow so' exige que ela exista). Devolve o proposicao-id."
  [ente]
  (let [pid (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (db-materia/inserir! tx {:ente-id ente :proposicao-id pid :tipo "projeto_lei" :ano 2026
                                 :sequencial 1 :urn-lex "urn:lex:br;ce;fortaleza:projeto.lei:2026;1"
                                 :ementa "Dispoe sobre X" :estado "protocolada"})))
    pid))

(defn- ator [ente ident] {:ente-id ente :identidade-id ident})

;; ---------- seguir materia existente -> aparece em 'minhas' ----------

(deftest seguir-projeta-em-minhas
  (let [ente (random-uuid) cidadao (random-uuid)
        pid  (criar-materia! ente)]
    (is (= "ativo" (:estado (controllers/seguir! *repo* (ator ente cidadao) pid))) "segue -> ativo")
    (let [minhas (controllers/meus-acompanhamentos *repo* (ator ente cidadao))]
      (is (= 1 (count minhas)))
      (is (= pid (:proposicao-id (first minhas))) "a materia seguida aparece")
      (is (= "Dispoe sobre X" (:ementa (first minhas))) "com o cabecalho da materia (JOIN same-schema)"))
    (is (empty? (controllers/meus-acompanhamentos *repo* (ator ente (random-uuid))))
        "OUTRO cidadao (mesmo ente) nao ve os follows alheios (escopo por seguidor)")))

;; ---------- GUARD: seguir materia inexistente -> nil (404 na borda) ----------

(deftest seguir-materia-inexistente-e-nil
  (let [ente (random-uuid) cidadao (random-uuid)]
    (is (nil? (controllers/seguir! *repo* (ator ente cidadao) (random-uuid)))
        "nao se segue uma materia que nao existe no portal (guard -> nil -> 404)")))

(deftest seguir-materia-de-outro-tenant-e-nil
  ;; o guard buscar-materia roda sob o tenant do ATOR (RLS): uma materia que existe NOUTRO ente e' invisivel
  ;; -> guard nil -> 404. Fecha o loop cross-tenant no caminho de ESCRITA (nao so' na leitura de 'minhas').
  (let [ente-a (random-uuid) ente-b (random-uuid) cidadao (random-uuid)
        pid    (criar-materia! ente-a)]
    (is (nil? (controllers/seguir! *repo* (ator ente-b cidadao) pid))
        "a materia do ente-a nao existe p/ o ator do ente-b (RLS no guard) -> nil")))

;; ---------- re-seguir REATIVA (UPSERT, sem duplicar) ----------

(deftest re-seguir-reativa-a-mesma-linha
  (let [ente (random-uuid) cidadao (random-uuid) a (ator ente cidadao)
        pid  (criar-materia! ente)]
    (controllers/seguir! *repo* a pid)
    (controllers/deixar-de-seguir! *repo* a pid)
    (is (empty? (controllers/meus-acompanhamentos *repo* a)) "cancelado some de 'minhas'")
    (is (= "ativo" (:estado (controllers/seguir! *repo* a pid))) "re-seguir -> ativo de novo")
    (let [minhas (controllers/meus-acompanhamentos *repo* a)]
      (is (= 1 (count minhas)) "UPSERT reativou a MESMA linha — nao criou uma 2a subscricao"))))

;; ---------- deixar de seguir = soft-cancel idempotente ----------

(deftest deixar-de-seguir-idempotente
  (let [ente (random-uuid) cidadao (random-uuid) a (ator ente cidadao)
        pid  (criar-materia! ente)]
    (controllers/seguir! *repo* a pid)
    (is (some? (controllers/deixar-de-seguir! *repo* a pid)) "1a vez cancela (linha afetada)")
    (is (nil? (controllers/deixar-de-seguir! *repo* a pid)) "2a vez = no-op (idempotente, nil)")
    (is (empty? (controllers/meus-acompanhamentos *repo* a)))))

;; ---------- RLS: cross-tenant ----------

(deftest rls-isola-acompanhamento-cross-tenant
  (let [ente-a (random-uuid) ente-b (random-uuid) cidadao (random-uuid)
        pid    (criar-materia! ente-a)]
    (controllers/seguir! *repo* (ator ente-a cidadao) pid)
    (is (empty? (controllers/meus-acompanhamentos *repo* (ator ente-b cidadao)))
        "o MESMO cidadao (identidade) noutro ente NAO ve o follow do ente-a (RLS)")))
