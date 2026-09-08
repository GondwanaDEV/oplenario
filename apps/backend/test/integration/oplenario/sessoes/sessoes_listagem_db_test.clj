(ns oplenario.sessoes.sessoes-listagem-db-test
  "INTEGRACAO (PG real): `db/sessao/listar-todas` (ledger de prontidao #16) — a leitura de GET /sessoes
  contra Postgres de verdade, via o Component REAL (`RepoSessoesPg`, mesmo padrao de
  `tribuna_repo_test.clj`). Cobre o que um fake nao pode provar: escopo por `ente-id` de verdade (RLS +
  WHERE), e o teto de linhas fail-closed (`logic/teto-de-sessoes-da-listagem-geral`, com `with-redefs`
  para nao precisar inserir centenas de linhas de verdade — mesma tecnica de
  `assiduidade-db-test/sessoes-acima-do-teto-do-recorte-lanca-fail-closed-e-o-driver-para-antes`)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.components.repositorio :as repo]
            [oplenario.sessoes.logic :as logic]))

(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*repo* (repo/->RepoSessoesPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

(defn- agendar! [ente]
  (:id (repo/agendar-sessao! *repo* ente {:id (random-uuid) :sessao-legislativa-id (random-uuid)
                                          :tipo-sessao "ordinaria" :modalidade "presencial"})))

;; ---------- sem sessao nenhuma -> lista vazia, nunca lanca ----------

(deftest ente-sem-sessao-nenhuma-lista-vazia
  (is (= [] (repo/listar-sessoes *repo* (random-uuid)))))

;; ---------- escopo por ENTE-ID: sessao de outro ente nao vaza ----------

(deftest listar-sessoes-nao-vaza-entre-entes
  (let [ente-a (random-uuid)
        ente-b (random-uuid)
        _sid (agendar! ente-a)]
    (is (= [] (repo/listar-sessoes *repo* ente-b))
        "sessao de ente-a nao aparece na listagem pedida com o ente-id de ente-b (RLS + WHERE ente_id)")
    (is (= 1 (count (repo/listar-sessoes *repo* ente-a))) "sanidade: ente-a ve a sua propria sessao")))

;; ---------- o teto de linhas e' fail-closed (nunca pagina truncada em silencio) ----------

(deftest sessoes-acima-do-teto-da-listagem-lanca-fail-closed-e-o-driver-para-antes
  (let [ente (random-uuid)]
    (dotimes [_ 3] (agendar! ente))
    (is (= 3 (count (repo/listar-sessoes *repo* ente)))
        "sanidade: as 3 sessoes existem e aparecem sem teto rebaixado")
    (with-redefs [logic/teto-de-sessoes-da-listagem-geral 2]
      (let [erro (try (repo/listar-sessoes *repo* ente) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? erro) "resultado acima do teto lanca, nunca devolve pagina truncada")
        (is (= :limite/sessoes-excedido (:tipo (ex-data erro))))
        (is (= 3 (:medido-ao-menos (ex-data erro)))
            "3 = o driver PAROU de materializar ali (:max-rows = teto+1 = 3)")
        (is (= 2 (:teto (ex-data erro))))))))
