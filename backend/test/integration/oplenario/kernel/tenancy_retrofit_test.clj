(ns oplenario.kernel.tenancy-retrofit-test
  "Retrofit da tenancy nas tabelas tenant PRE-EXISTENTES (carry CRITICO da review F0.3): as migrations
  4 (paineis) e 5 (compliance) adiaram a RLS de proposito; F1.0 as faz herdar o MESMO padrao da
  exemplar shared.tenancy_prova (FORCE RLS + tenant_isolation + WITH CHECK) ANTES de qualquer dado
  real. Prova a 3 dimensoes em UMA tabela representativa de cada modulo. PG real.

  Tambem trava a postura de runtime: existe um role LOGIN NOBYPASSRLS (oplenario_pool) membro de
  oplenario_app — o pool de producao conecta como ele, nao mais superuser."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
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

(use-fixtures :each
  (fn [t]
    (jdbc/execute! *ds* ["TRUNCATE compliance.prazo_dominio_ativo"])
    (jdbc/execute! *ds* ["TRUNCATE paineis.notificacao_entrega"])
    (t)))

(defn- seed-prazo! [ente-id chave]
  ;; setup como dono/superuser (bypassa RLS) p/ semear qualquer ente.
  (jdbc/execute-one! *ds*
                     ["INSERT INTO compliance.prazo_dominio_ativo
                         (id, ente_id, template_chave, objeto_tipo, objeto_id, vence_em)
                       VALUES (?, ?, ?, 'proposicao', ?, '2026-12-31')"
                      (random-uuid) ente-id chave (random-uuid)]))

(defn- chaves [tx]
  (set (map :prazo_dominio_ativo/template_chave
            (jdbc/execute! tx ["SELECT template_chave FROM compliance.prazo_dominio_ativo"]))))

(deftest compliance-rls-isola-cross-tenant
  (let [a (random-uuid) b (random-uuid)]
    (seed-prazo! a "remessa_a")
    (seed-prazo! b "remessa_b")
    (is (= #{"remessa_a"} (tenancy/com-tenant* *ds* a chaves)) "ente A so ve obrigacao de A")
    (is (= #{"remessa_b"} (tenancy/com-tenant* *ds* b chaves)) "ente B so ve obrigacao de B")))

(deftest compliance-rls-falha-fechado-sem-tenant
  (seed-prazo! (random-uuid) "x")
  (is (= #{} (jdbc/with-transaction [tx *ds*]
               (jdbc/execute-one! tx ["SET LOCAL ROLE oplenario_app"])
               (chaves tx)))
      "oplenario_app SEM app.ente_id -> RLS nega tudo (fail-closed) mesmo com dado presente"))

(deftest compliance-with-check-bloqueia-insert-cross-tenant
  (let [a (random-uuid) b (random-uuid)]
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* a
                                      (fn [tx]
                                        (jdbc/execute-one! tx
                                                           ["INSERT INTO compliance.prazo_dominio_ativo
                                                               (id, ente_id, template_chave, objeto_tipo, objeto_id, vence_em)
                                                             VALUES (?, ?, 'cross', 'proposicao', ?, '2026-12-31')"
                                                            (random-uuid) b (random-uuid)]))))
        "sessao do ente A NAO insere obrigacao com ente_id=B (WITH CHECK)")))

(deftest paineis-ledger-rls-isola-cross-tenant
  (let [a (random-uuid) b (random-uuid)]
    (jdbc/execute-one! *ds* ["INSERT INTO paineis.notificacao_entrega (id, ente_id, destinatario, canal, idempotency_key)
                              VALUES (?, ?, 'x@a', 'email', ?)" (random-uuid) a (str "k" a)])
    (jdbc/execute-one! *ds* ["INSERT INTO paineis.notificacao_entrega (id, ente_id, destinatario, canal, idempotency_key)
                              VALUES (?, ?, 'x@b', 'email', ?)" (random-uuid) b (str "k" b)])
    (let [conta (fn [tx] (:c (jdbc/execute-one! tx ["SELECT count(*) c FROM paineis.notificacao_entrega"])))]
      (is (= 1 (tenancy/com-tenant* *ds* a conta)) "ledger de notificacao isola por ente (A)")
      (is (= 1 (tenancy/com-tenant* *ds* b conta)) "ledger de notificacao isola por ente (B)"))))

(deftest motor-regra-tenant-tem-rls-forcada
  ;; a unica tabela tenant do schema motor (binding de regra por ente) nao pode ficar sem RLS quando a
  ;; F2 ligar o resolvedor — catalogo confirma ENABLE + FORCE (column-agnostico).
  (let [r (jdbc/execute-one! *ds*
                             ["SELECT c.relrowsecurity, c.relforcerowsecurity
                               FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                               WHERE n.nspname = 'motor' AND c.relname = 'compliance_regra_tenant'"]
                             {:builder-fn rs/as-unqualified-maps})]
    (is (true? (:relrowsecurity r)) "motor.compliance_regra_tenant tem RLS habilitada")
    (is (true? (:relforcerowsecurity r)) "motor.compliance_regra_tenant tem FORCE RLS (nem o dono bypassa)")))

(deftest outbox-privilegio-minimo-por-papel
  ;; o codigo de dominio (oplenario_app) so EMITE no outbox; le/marca/poda e' do relay (oplenario_relay).
  (let [priv (fn [role tabela ac]
               (:p (jdbc/execute-one! *ds* [(str "SELECT has_table_privilege(?, ?, ?) AS p") role tabela ac]
                                      {:builder-fn rs/as-unqualified-maps})))]
    (is (true? (priv "oplenario_app" "shared.outbox" "INSERT")) "dominio EMITE no outbox")
    (is (false? (priv "oplenario_app" "shared.outbox" "SELECT")) "dominio NAO le o outbox (sem leak cross-tenant)")
    (is (false? (priv "oplenario_app" "shared.outbox" "UPDATE")) "dominio NAO marca o outbox")
    (is (false? (priv "oplenario_app" "shared.evento_consumido" "SELECT")) "dominio nao toca o ledger de inbox")
    (is (true? (priv "oplenario_relay" "shared.outbox" "SELECT")) "relay le o outbox (drena cross-tenant)")
    (is (true? (priv "oplenario_relay" "shared.outbox" "UPDATE")) "relay marca processed_at")
    (is (true? (priv "oplenario_relay" "shared.evento_consumido" "DELETE")) "relay poda o ledger de inbox")))

(deftest pool-role-existe-e-e-nobypassrls
  (let [r (jdbc/execute-one! *ds*
                             ["SELECT rolcanlogin, rolbypassrls,
                                 pg_has_role('oplenario_pool', 'oplenario_app', 'MEMBER') AS membro
                               FROM pg_roles WHERE rolname = 'oplenario_pool'"]
                             {:builder-fn rs/as-unqualified-maps})]
    (is (some? r) "o role de runtime oplenario_pool existe")
    (is (true? (:rolcanlogin r)) "oplenario_pool e LOGIN (o pool conecta como ele)")
    (is (false? (:rolbypassrls r)) "oplenario_pool NAO bypassa RLS (defesa em profundidade)")
    (is (true? (:membro r)) "oplenario_pool e membro de oplenario_app (herda os grants por INHERIT)")))
