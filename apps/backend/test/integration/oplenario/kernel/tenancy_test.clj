(ns oplenario.kernel.tenancy-test
  "Leak test da tenancy (§22.2 + fundacao #2): a RLS isola por ente_id e esconde o lote nao-efetivado
  em sessao normal (ANPD). com-tenant* vira oplenario_app (RLS aplica) + seta o tenant. O setup
  (inserir!) roda como oplenario (superuser) -> bypassa RLS p/ semear dados de qualquer ente. PG real."
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
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(use-fixtures :each
  (fn [t] (jdbc/execute! *ds* ["TRUNCATE shared.tenancy_prova"]) (t)))

(defn- inserir! [ente-id lote-id efetivado? dado]
  ;; setup como dono/superuser (bypassa RLS). efetivado_em via SQL now()/NULL.
  (jdbc/execute-one! *ds* [(str "INSERT INTO shared.tenancy_prova (id, ente_id, lote_id, efetivado_em, dado)
                                 VALUES (?, ?, ?, " (if efetivado? "now()" "NULL") ", ?)")
                           (random-uuid) ente-id lote-id dado]))

(defn- dados [tx]
  (set (map :tenancy_prova/dado (jdbc/execute! tx ["SELECT dado FROM shared.tenancy_prova"]))))

(deftest rls-isola-cross-tenant
  (let [a (random-uuid) b (random-uuid)]
    (inserir! a nil true "a1")
    (inserir! b nil true "b1")
    (is (= #{"a1"} (tenancy/com-tenant* *ds* a dados)) "sessao do ente A so ve dados de A")
    (is (= #{"b1"} (tenancy/com-tenant* *ds* b dados)) "sessao do ente B so ve dados de B")))

(deftest rls-esconde-lote-nao-efetivado-do-proprio-ente
  (let [ente (random-uuid) lote (random-uuid)]
    (inserir! ente nil true "efetivado")
    (inserir! ente lote false "nao-efetivado")
    (is (= #{"efetivado"} (tenancy/com-tenant* *ds* ente dados))
        "sessao normal NAO ve o lote nao-efetivado do PROPRIO ente (ANPD art.48)")
    (is (= #{"efetivado" "nao-efetivado"} (tenancy/com-reconciliacao* *ds* ente lote dados))
        "com-reconciliacao* (app.ver_lote) ve o lote em reconciliacao")))

(deftest ente-da-sessao-le-o-tenant-de-volta-e-FALHA-LOUD-sem-ele
  ;; 3-B: a camada `relacoes/` do motor nao recebe `ente` (a assinatura de relacao e' `(fn tx arg…)`, e
  ;; §4-bis/C2 diz que a Casa e' implicita na tx). Quando a relacao delega a uma fn de `db/` que exige
  ;; `ente-id` explicito, este e' o caminho — e ele tem de LANCAR quando nao ha' tenant. Devolver nil
  ;; faria a consulta a jusante casar zero linhas e o fato responder `falso` em silencio: um guard de
  ;; tramitacao negaria para sempre, e o sintoma seria "a Casa nao permite este ato", nunca a causa.
  (let [ente (random-uuid)]
    (is (= ente (tenancy/com-tenant* *ds* ente tenancy/ente-da-sessao))
        "dentro de com-tenant*: devolve o MESMO ente que foi setado, como uuid")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"app.ente_id nao setado"
          (jdbc/with-transaction [tx *ds*]
            (jdbc/execute-one! tx ["SET LOCAL ROLE oplenario_app"])
            (tenancy/ente-da-sessao tx)))
        "fora de com-tenant*: LANCA (nunca nil silencioso)")))

(deftest rls-falha-fechado-sem-tenant-setado
  (let [a (random-uuid)]
    (inserir! a nil true "a1")
    (is (= #{} (jdbc/with-transaction [tx *ds*]
                 (jdbc/execute-one! tx ["SET LOCAL ROLE oplenario_app"])
                 (dados tx)))
        "como oplenario_app SEM app.ente_id -> RLS nega tudo (fail-closed, sem erro)")))

(deftest rls-with-check-bloqueia-insert-cross-tenant
  (let [a (random-uuid) b (random-uuid)]
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* a
                                      (fn [tx]
                                        (jdbc/execute-one! tx
                                                           ["INSERT INTO shared.tenancy_prova (id, ente_id, efetivado_em, dado)
                                                             VALUES (?, ?, now(), 'cross')"
                                                            (random-uuid) b]))))
        "oplenario_app com app.ente_id=A NAO insere linha com ente_id=B (WITH CHECK)")))
