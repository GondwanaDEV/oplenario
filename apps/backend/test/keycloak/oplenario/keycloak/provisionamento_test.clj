(ns oplenario.keycloak.provisionamento-test
  "INTEGRACAO gated: exige o Keycloak do docker (--profile auth) rodando. Prova provisionar-realm!/
  criar-usuario!/resetar-mfa! contra a admin-API real — nao roda no CI (sem Keycloak la'; ver
  tests.edn/:keycloak e ci.yml --skip :keycloak)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.kernel.components.idp :as idp]
            [oplenario.kernel.components.keycloak-idp :as kc]))

(def ^:private base-url (or (System/getenv "KEYCLOAK_BASE_URL") "http://localhost:8090"))

(def ^:private config
  {:base-url base-url :realm-prefixo "ente-" :audiencia "oplenario-backend"
   :admin-usuario "admin" :admin-senha "admin" :jwks-cache-ttl-s 600})

(def ^:dynamic *idp* nil)

(use-fixtures :each
  (fn [t]
    (binding [*idp* (component/start (kc/keycloak-idp config))]
      (try (t) (finally (component/stop *idp*))))))

(deftest provisionar-realm-e-idempotente
  (let [ente-id (random-uuid)
        r1 (idp/provisionar-realm! *idp* ente-id)
        r2 (idp/provisionar-realm! *idp* ente-id)]
    (is (= (str "ente-" ente-id) (:realm r1)))
    (is (= r1 r2) "chamar 2x nao falha nem duplica")))

(deftest criar-usuario-persiste-o-atributo-identidade-id
  (let [ente-id (random-uuid)
        iid (random-uuid)
        _ (idp/provisionar-realm! *idp* ente-id)
        resultado (idp/criar-usuario! *idp* ente-id {:identidade-id iid :nome "Vereadora Teste" :email "teste@example.com"})]
    (is (some? (:keycloak-user-id resultado)) "devolve o id interno do Keycloak (do header Location)")))

(deftest resetar-mfa-sem-usuario-lanca
  (let [ente-id (random-uuid)]
    (idp/provisionar-realm! *idp* ente-id)
    (is (thrown? clojure.lang.ExceptionInfo (idp/resetar-mfa! *idp* ente-id (random-uuid)))
        "identidade sem usuario naquele realm -> lanca (nao inventa sucesso)")))

(deftest resetar-mfa-sem-credenciais-mfa-nao-remove-nada
  (let [ente-id (random-uuid)
        iid (random-uuid)]
    (idp/provisionar-realm! *idp* ente-id)
    (idp/criar-usuario! *idp* ente-id {:identidade-id iid :nome "Servidor Teste" :email "servidor@example.com"})
    (let [r (idp/resetar-mfa! *idp* ente-id iid)]
      (is (= 0 (:removidas r)) "usuario recem-criado nao tem OTP/WebAuthn configurado ainda"))))
