(ns oplenario.paineis.notificacao-caixa-test
  "INTEGRACAO (PG real) — Onda E fatia 1: a tabela da INBOX (`paineis.notificacao_caixa`, mig 0062).
  Task 1 prova SO' o contrato da TABELA: isolamento de tenant (FORCE RLS + WITH CHECK) e a UNIQUE
  (ente_id, idempotency_key) que torna o redrive um no-op. As fns de `db/` entram nas Tasks 4/6/7."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)]
        (try (t) (finally (component/stop c)))))))

(defn- inserir! [ente destinatario chave]
  (tenancy/com-tenant* *ds* ente
    (fn [tx]
      (jdbc/execute-one! tx
        ["INSERT INTO paineis.notificacao_caixa
            (id, ente_id, destinatario_identidade_id, categoria, assunto, corpo,
             objeto_tipo, objeto_id, idempotency_key)
          VALUES (?, ?, ?, 'norma_publicada', 'assunto', 'corpo', 'proposicao', ?, ?)
          ON CONFLICT (ente_id, idempotency_key) DO NOTHING
          RETURNING id"
         (random-uuid) ente destinatario (random-uuid) chave]))))

(defn- contar [ente]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (:c (jdbc/execute-one! tx ["SELECT count(*) AS c FROM paineis.notificacao_caixa"])))))

(deftest linha-nasce-nao-lida
  (let [ente (random-uuid) dest (random-uuid)]
    (is (some? (inserir! ente dest "k1")) "insercao devolve a linha")
    (is (= 1 (contar ente)))
    (is (nil? (tenancy/com-tenant* *ds* ente
                (fn [tx] (:notificacao_caixa/lida_em
                          (jdbc/execute-one! tx ["SELECT lida_em FROM paineis.notificacao_caixa"])))))
        "lida_em nasce NULL = nao lida")))

(deftest unique-por-chave-de-idempotencia
  (let [ente (random-uuid) dest (random-uuid)]
    (inserir! ente dest "mesma-chave")
    (is (nil? (inserir! ente dest "mesma-chave")) "ON CONFLICT DO NOTHING -> 2a insercao e' no-op")
    (is (= 1 (contar ente)) "uma unica linha para a mesma chave logica")))

(deftest isolamento-de-tenant
  (let [ente-a (random-uuid) ente-b (random-uuid) dest (random-uuid)]
    (inserir! ente-a dest "k-a")
    (is (= 1 (contar ente-a)))
    (is (= 0 (contar ente-b)) "a notificacao de uma Casa nunca aparece na outra (FORCE RLS)")))

(deftest with-check-barra-escrita-cross-tenant
  (let [ente-a (random-uuid) ente-b (random-uuid)]
    (is (thrown? Exception
          (tenancy/com-tenant* *ds* ente-a
            (fn [tx]
              (jdbc/execute-one! tx
                ["INSERT INTO paineis.notificacao_caixa
                    (id, ente_id, destinatario_identidade_id, categoria, assunto, corpo,
                     objeto_tipo, objeto_id, idempotency_key)
                  VALUES (?, ?, ?, 'norma_publicada', 'a', 'c', 'proposicao', ?, 'k-forjada')"
                 (random-uuid) ente-b (random-uuid) (random-uuid)]))))
        "WITH CHECK barra gravar linha de OUTRO ente mesmo com o GUC do proprio")))
