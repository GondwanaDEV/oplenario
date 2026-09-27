(ns oplenario.admin-sistema.idp-admin-test
  "Unit (sem rede): o IdP do operador (ADR-0016). (1) So' o token do realm do operador, com a audiencia do console e
  o `operador-id`, vira operador — um token de realm de Casa, assinado pela mesma chave, NAO. (2) O realm nasce com
  senha + chave fisica obrigatorias, chave de seguranca (cross-platform) e sem grant direto de senha."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.stuartsierra.component :as component]
            [oplenario.admin-sistema.components.idp-admin :as op]
            [oplenario.kernel.components.keycloak-idp :as kc])
  (:import (com.auth0.jwk Jwk JwkProvider)
           (com.auth0.jwt JWT)
           (com.auth0.jwt.algorithms Algorithm)
           (java.security KeyPairGenerator)
           (java.security.interfaces RSAPrivateKey RSAPublicKey)
           (java.time Instant)
           (java.util Arrays Base64)))

(def ^:private config {:base-url "http://kc:8080" :base-url-publico "http://localhost:8090" :realm "operacao"
                       :audiencia "oplenario-operacao" :client-id "oplenario-console" :jwks-cache-ttl-s 600
                       :admin-usuario "admin" :admin-senha "admin"
                       :redirect-uris ["http://localhost:3000/api/operacao/callback"]
                       :web-origins ["http://localhost:3000"]
                       :sessao {:absoluta-h 8 :ociosa-min 15}})

(def ^:private par (let [g (KeyPairGenerator/getInstance "RSA")] (.initialize g 2048) (.generateKeyPair g)))
(def ^:private pub ^RSAPublicKey (.getPublic par))
(def ^:private priv ^RSAPrivateKey (.getPrivate par))

(defn- sem-sinal [^bytes bs] (if (and (> (alength bs) 1) (zero? (aget bs 0))) (Arrays/copyOfRange bs 1 (alength bs)) bs))
(defn- b64 [^bytes bs] (.encodeToString (Base64/getUrlEncoder) bs))
(def ^:private jwk (Jwk/fromValues {"kty" "RSA" "kid" "k1" "use" "sig" "alg" "RS256"
                                     "n" (b64 (sem-sinal (.toByteArray (.getModulus pub))))
                                     "e" (b64 (sem-sinal (.toByteArray (.getPublicExponent pub))))}))

(defn- idp [] (component/start (op/keycloak-operacao config (fn [_ _] (reify JwkProvider (get [_ _] jwk))))))

(defn- token [{:keys [iss aud operador-id] :or {iss "http://localhost:8090/realms/operacao" aud "oplenario-operacao"}}]
  (cond-> (-> (JWT/create) (.withIssuer ^String iss) (.withSubject "kc-sub") (.withKeyId "k1")
              (.withAudience (into-array String [aud])) (.withExpiresAt (.plusSeconds (Instant/now) 60)))
    operador-id (.withClaim "operador-id" (str operador-id))
    true (.sign (Algorithm/RSA256 pub priv))))

(deftest token-do-realm-do-operador-vira-operador
  (let [oid (random-uuid)]
    (is (= oid (:operador-id (op/verificar-token-operador (idp) (token {:operador-id oid})))))
    (is (= oid (:operador-id (op/verificar-token-operador (idp) (token {:iss "http://kc:8080/realms/operacao"
                                                                        :operador-id oid}))))
        "a URL interna tambem e' emissor aceito (split-horizon)")))

(deftest nada-alem-do-realm-do-operador-passa
  (let [oid (random-uuid)]
    (testing "o realm de uma Casa, mesmo com a mesma chave e o mesmo claim, nao e' emissor aceito"
      (is (nil? (op/verificar-token-operador (idp) (token {:iss (str "http://localhost:8090/realms/ente-" (random-uuid))
                                                           :operador-id oid})))))
    (is (nil? (op/verificar-token-operador (idp) (token {:iss "http://outro/realms/operacao" :operador-id oid}))))
    (is (nil? (op/verificar-token-operador (idp) (token {:aud "oplenario-backend" :operador-id oid})))
        "audiencia das Casas nao vale no console")
    (is (nil? (op/verificar-token-operador (idp) (token {}))) "sem operador-id nao e' operador")
    (is (nil? (op/verificar-token-operador (idp) "nao-e-jwt")))))

(deftest o-token-do-operador-nao-e-token-de-casa
  (let [casa (component/start (kc/keycloak-idp {:base-url "http://kc:8080" :base-url-publico "http://localhost:8090"
                                                 :realm-prefixo "ente-" :audiencia "oplenario-operacao"
                                                 :jwks-cache-ttl-s 600}
                                                (fn [_ _] (reify JwkProvider (get [_ _] jwk)))))]
    (is (nil? (kc/verificar-token* casa (token {:operador-id (random-uuid)})))
        "o realm 'operacao' nao passa na allowlist das Casas, nem com a mesma audiencia")))

;; ---- o realm ----

(defn- fake-admin! [chamadas]
  (fn [_http _token metodo caminho corpo _base]
    (swap! chamadas conj {:metodo metodo :caminho caminho :corpo corpo})
    (cond
      (and (= :get metodo) (re-matches #"/admin/realms/operacao" caminho)) {:status 404}
      (str/ends-with? caminho "/users/profile") (if (= :get metodo) {:status 200 :corpo {:attributes []}} {:status 200})
      (and (= :get metodo) (str/ends-with? caminho "/authentication/flows")) {:status 200 :corpo []}
      (and (= :get metodo) (str/ends-with? caminho "/executions")) {:status 200 :corpo []}
      (str/includes? caminho "/clients?clientId=") {:status 200 :corpo []}
      (= :post metodo) {:status 201}
      (= :put metodo) {:status 204}
      :else {:status 200 :corpo []})))

(defn- provisionar! []
  (let [chamadas (atom [])]
    (with-redefs-fn {#'kc/admin-token! (fn [_ _] "t") #'kc/admin-req! (fake-admin! chamadas)}
      #(op/provisionar-realm-operacao! (op/map->KeycloakOperacao {:config config})))
    @chamadas))

(deftest realm-do-operador-exige-senha-e-chave-fisica
  (let [chamadas (provisionar!)
        politica (some #(when (and (= :put (:metodo %)) (= "/admin/realms/operacao" (:caminho %))
                                   (:browserFlow (:corpo %)))
                          (:corpo %))
                       chamadas)
        subfluxo (some #(when (str/ends-with? (:caminho %) "/executions/flow") (:corpo %)) chamadas)
        no-sub (->> chamadas
                    (filter #(str/includes? (:caminho %) "/flows/operacao-senha-e-chave/executions/execution"))
                    (map (comp :provider :corpo)) set)]
    (is (some #(and (= :post (:metodo %)) (= "operacao" (:realm (:corpo %)))) chamadas) "cria o realm")
    (is (= op/fluxo-navegador (:browserFlow politica)) "o login do navegador e' o fluxo do operador")
    (is (= "cross-platform" (:webAuthnPolicyAuthenticatorAttachment politica)) "chave de seguranca, nao o aparelho")
    (is (false? (:registrationAllowed politica)))
    (is (true? (:bruteForceProtected politica)))
    (is (= 900 (:ssoSessionIdleTimeout politica)))
    (is (= "operacao-senha-e-chave" (:alias subfluxo)))
    (is (= #{"auth-username-password-form" "webauthn-authenticator"} no-sub) "senha E chave no mesmo subfluxo")
    (testing "os clients do console nao aceitam senha direta"
      (let [clients (filter #(and (= :post (:metodo %)) (str/ends-with? (:caminho %) "/clients")) chamadas)]
        (is (= #{"oplenario-operacao" "oplenario-console"} (set (map (comp :clientId :corpo) clients))))
        (is (every? #(false? (:directAccessGrantsEnabled (:corpo %))) clients))
        (is (= "S256" (get-in (some #(when (= "oplenario-console" (:clientId (:corpo %))) (:corpo %)) clients)
                              [:attributes "pkce.code.challenge.method"])))))
    (is (some #(and (= :put (:metodo %)) (str/ends-with? (:caminho %) "/required-actions/webauthn-register")) chamadas))))

(deftest aaguids-restringem-o-modelo-de-chave
  (is (= ["cb69481e-8ff7-4039-93ec-0a2729a154a8"]
         (:webAuthnPolicyAcceptableAaguids (op/politica-do-realm (assoc config :aaguids ["cb69481e-8ff7-4039-93ec-0a2729a154a8"])))))
  (is (not (contains? (op/politica-do-realm config) :webAuthnPolicyAcceptableAaguids))))
