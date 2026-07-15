(ns oplenario.identidade.db-test
  "INTEGRACAO (PG real): o modulo identidade. Prova a separacao disc.1 (identidade SUPRATENANT atravessa
  entes; vinculo TENANT isola), o split de privilegio (dominio NAO le CPF), o broker gov.br (com guarda
  anti-takeover), o snapshot de papeis (RBAC), o ciclo de consentimento (LGPD) e a relacao é_o_próprio."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.identidade.components.repositorio :as repo]
            [oplenario.identidade.db.identidade :as id]
            [oplenario.identidade.db.vinculo :as vinc]
            [oplenario.identidade.models.identidade :as mod]
            [oplenario.identidade.relacoes.identidade :as rel]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- dv [ds]
  (let [r (mod (reduce + (map * ds (range (inc (count ds)) 1 -1))) 11)]
    (if (< r 2) 0 (- 11 r))))

(defn- cpf-valido []
  ;; gera um CPF com digito verificador correto (mesmo algoritmo de mod/valido-cpf?).
  (let [base (vec (repeatedly 9 #(rand-int 10)))
        d1 (dv base)
        d2 (dv (conj base d1))]
    (apply str (concat base [d1 d2]))))

(defn- criar-identidade-fixture!
  "Fixture: cria a identidade supratenant (sobre *ds*) e devolve {:id ...} — envelope de mapa p/
  combinar com o padrao dos demais helpers do modulo (id/inserir! ja devolve so o uuid canonico)."
  [cpf nome]
  {:id (id/inserir! *ds* {:id (random-uuid) :cpf cpf :nome nome})})

(defn- repo-identidade
  "O Repo-Component (ADR-0001 §3) construido sobre o *ds* do teste — mesmo padrao dos demais testes de
  integracao do modulo (autenticacao-test, repo-test)."
  []
  (assoc (repo/repositorio) :datasource {:ds *ds*}))

(deftest identidade-supratenant-round-trip-e-broker-govbr
  (let [iid (random-uuid) cpf (cpf-valido)
        ret (id/inserir! *ds* {:id iid :cpf cpf :nome "Joao da Silva"})]
    (is (= iid ret) "inserir! retorna o id canonico")
    ;; conflito por CPF: retorna o id EXISTENTE (nao o novo) — base do enrollment idempotente
    (is (= iid (id/inserir! *ds* {:id (random-uuid) :cpf cpf :nome "Joao"})) "re-enrollment do mesmo CPF retorna o id canonico")
    (let [i (id/por-cpf *ds* cpf)]
      (is (= iid (:id i)) "resolve CPF -> identidade")
      (is (m/validate mod/Identidade i) "identidade bate o model"))
    (let [sub (str "govbr-" (random-uuid))]
      (id/vincular-externa! *ds* {:id (random-uuid) :identidade-id iid :provedor "gov_br" :sub sub})
      (is (= iid (id/identidade-por-sub *ds* "gov_br" sub)) "resolve (gov_br, sub) -> identidade"))
    (is (nil? (id/identidade-por-sub *ds* "gov_br" (str "inexistente-" (random-uuid)))) "sub desconhecido -> nil")))

(deftest cpf-invalido-e-rejeitado
  (is (thrown? AssertionError (id/inserir! *ds* {:id (random-uuid) :cpf "12345678900" :nome "X"}))
      "CPF com digito verificador invalido e' rejeitado")
  (is (thrown? AssertionError (id/inserir! *ds* {:id (random-uuid) :cpf "11111111111" :nome "X"}))
      "CPF de 11 digitos iguais e' rejeitado"))

(deftest broker-govbr-guarda-anti-takeover
  (let [a (random-uuid) b (random-uuid) sub (str "reciclado-" (random-uuid))]
    (id/inserir! *ds* {:id a :cpf (cpf-valido) :nome "Pessoa A"})
    (id/inserir! *ds* {:id b :cpf (cpf-valido) :nome "Pessoa B"})
    (id/vincular-externa! *ds* {:id (random-uuid) :identidade-id a :provedor "gov_br" :sub sub})
    ;; re-vincular o MESMO sub a outra identidade -> takeover -> LANCA (nao no-op silencioso)
    (is (thrown? Exception
                 (id/vincular-externa! *ds* {:id (random-uuid) :identidade-id b :provedor "gov_br" :sub sub}))
        "re-bind do sub a identidade diferente lanca (anti-takeover)")
    (is (= a (id/identidade-por-sub *ds* "gov_br" sub)) "o sub segue apontando p/ a identidade original")))

(deftest split-de-privilegio-dominio-nao-le-cpf
  (id/inserir! *ds* {:id (random-uuid) :cpf (cpf-valido) :nome "Segredo"})
  (is (thrown? Exception
               (jdbc/with-transaction [tx *ds*]
                 (jdbc/execute-one! tx ["SET LOCAL ROLE oplenario_app"])
                 (jdbc/execute-one! tx ["SELECT count(*) FROM identidade.identidade"])))
      "o role de dominio (oplenario_app) NAO tem privilegio de ler a tabela supratenant de CPF"))

(deftest vinculo-e-papel-isolam-por-tenant
  (let [a (random-uuid) b (random-uuid)
        iid (random-uuid)]
    (id/inserir! *ds* {:id iid :cpf (cpf-valido) :nome "Multi Vinculo"})
    ;; mesmo CPF: vereador no ente A + cidadao no ente B (disc.1 — multi-vinculo)
    (tenancy/com-tenant* *ds* a
      (fn [tx]
        (vinc/criar! tx {:id (random-uuid) :ente-id a :identidade-id iid :tipo "vereador"})
        (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id a :identidade-id iid :papel "vereador"})
        (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id a :identidade-id iid :papel "presidente_mesa"})))
    (tenancy/com-tenant* *ds* b
      (fn [tx] (vinc/criar! tx {:id (random-uuid) :ente-id b :identidade-id iid :tipo "cidadao"})))
    (is (= ["vereador"] (mapv :tipo (tenancy/com-tenant* *ds* a (fn [tx] (vinc/vinculos-de tx a iid))))) "A ve o vinculo vereador")
    (is (= #{"vereador" "presidente_mesa"} (tenancy/com-tenant* *ds* a (fn [tx] (vinc/papeis-de tx a iid)))) "A ve os 2 papeis")
    (is (every? #(m/validate mod/Vinculo %) (tenancy/com-tenant* *ds* a (fn [tx] (vinc/vinculos-de tx a iid)))) "vinculo bate o model")
    (is (= ["cidadao"] (mapv :tipo (tenancy/com-tenant* *ds* b (fn [tx] (vinc/vinculos-de tx b iid))))) "B ve so o vinculo cidadao")
    (is (= #{} (tenancy/com-tenant* *ds* b (fn [tx] (vinc/papeis-de tx b iid)))) "B nao ve papeis de A (RLS)")))

(deftest consentimento-lgpd-ciclo
  (let [ente (random-uuid) iid (random-uuid) cid (random-uuid)]
    (id/inserir! *ds* {:id iid :cpf (cpf-valido) :nome "Cidada"})
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (vinc/registrar-consentimento! tx {:id cid :ente-id ente :identidade-id iid
                                           :finalidade "notificacao_email" :base-legal "consentimento" :versao-termo "v1"})))
    (is (= 1 (count (tenancy/com-tenant* *ds* ente (fn [tx] (vinc/consentimentos-ativos tx ente iid))))) "1 consentimento ativo")
    (is (true? (tenancy/com-tenant* *ds* ente (fn [tx] (vinc/revogar-consentimento! tx cid)))) "revogar confirma true")
    (is (false? (tenancy/com-tenant* *ds* ente (fn [tx] (vinc/revogar-consentimento! tx cid)))) "revogar de novo = false (ja revogado)")
    (is (= 0 (count (tenancy/com-tenant* *ds* ente (fn [tx] (vinc/consentimentos-ativos tx ente iid))))) "apos revogar, 0 ativos")))

(deftest e-o-proprio-relacao-transversal
  (let [x (random-uuid) y (random-uuid)]
    (is (true? (rel/e-o-proprio? x x)) "mesma identidade")
    (is (false? (rel/e-o-proprio? x y)) "identidades distintas")
    (is (false? (rel/e-o-proprio? nil nil)) "ator nil -> falso (fail-closed)")
    (is (true? (rel/e-o-proprio? :tx-ignorada x x)) "assinatura com tx (motor) ignora a tx")
    (is (= #{"é_o_próprio"} (set (keys rel/relacoes))) "registro expoe a relacao transversal")))

(deftest conceder-acesso-idempotente-numa-tx
  (let [ente (random-uuid)
        ident (:id (criar-identidade-fixture! (cpf-valido) "Helena Matos"))
        repo (repo-identidade)
        v {:id (random-uuid) :ente-id ente :identidade-id ident :tipo "vereador" :estado "ativo"}
        r1 (repo/conceder-acesso! repo ente v ["vereador"])
        r2 (repo/conceder-acesso! repo ente (assoc v :id (random-uuid)) ["vereador"])]
    (is (= (:vinculo-id r1) (:vinculo-id r2))
        "idempotente por (ente,identidade,tipo): repetir devolve o vinculo CANONICO, nao duplica nem estoura")
    (is (= 1 (count (repo/vinculos-de repo ente ident))) "um vinculo, nao dois")
    (is (= #{"vereador"} (repo/papeis-de repo ente ident)) "papel concedido")))
