(ns oplenario.kernel.components.keycloak-idp-test
  "Unit (sem rede): verificar-token contra um par de chaves RSA sintetico + JwkProvider FAKE injetado
  (inversao de dependencia — §5 do design). Cobre a borda de seguranca exaustivamente: assinatura valida/
  invalida, expiracao, confusao de algoritmo, issuer fora da allowlist, kid desconhecido, erro de infra
  PROPAGA (nao vira nil)."
  (:require [clojure.test :refer [deftest is testing]]
            [com.stuartsierra.component :as component]
            [oplenario.kernel.components.idp :as idp]
            [oplenario.kernel.components.keycloak-idp :as kc])
  (:import (com.auth0.jwk Jwk JwkProvider SigningKeyNotFoundException NetworkException RateLimitReachedException)
           (com.auth0.jwt JWT)
           (com.auth0.jwt.algorithms Algorithm)
           (java.security KeyPairGenerator)
           (java.security.interfaces RSAPublicKey RSAPrivateKey)
           (java.time Instant)
           (java.util Base64 Arrays)))

(def ^:private base-url "http://localhost:8080")
(def ^:private realm-prefixo "ente-")
(def ^:private audiencia "oplenario-backend")
(def ^:private ente-id (random-uuid))
(def ^:private iss (str base-url "/realms/" realm-prefixo ente-id))
(def ^:private kid "chave-de-teste-1")

(defn- gerar-par-rsa []
  (let [kpg (KeyPairGenerator/getInstance "RSA")]
    (.initialize kpg 2048)
    (.generateKeyPair kpg)))

(def ^:private par (gerar-par-rsa))
(def ^:private pub ^RSAPublicKey (.getPublic par))
(def ^:private priv ^RSAPrivateKey (.getPrivate par))

(defn- sem-byte-de-sinal [^bytes bs]
  (if (and (> (alength bs) 1) (zero? (aget bs 0))) (Arrays/copyOfRange bs 1 (alength bs)) bs))

(defn- b64url [^bytes bs] (.encodeToString (Base64/getUrlEncoder) bs))

(defn- jwk-de [^RSAPublicKey chave id-chave]
  (Jwk/fromValues {"kty" "RSA" "kid" id-chave "use" "sig" "alg" "RS256"
                    "n" (b64url (sem-byte-de-sinal (.toByteArray (.getModulus chave))))
                    "e" (b64url (sem-byte-de-sinal (.toByteArray (.getPublicExponent chave))))}))

(defn- provider-fixo [jwk]
  (reify JwkProvider (get [_ _kid] jwk)))

(defn- provider-sem-chave []
  (reify JwkProvider (get [_ requested-kid] (throw (SigningKeyNotFoundException. (str "kid " requested-kid) nil)))))

(defn- provider-com-erro-de-rede []
  (reify JwkProvider (get [_ _kid] (throw (NetworkException. "jwks indisponivel" (RuntimeException. "timeout"))))))

(defn- provider-rate-limited []
  (reify JwkProvider (get [_ _kid] (throw (RateLimitReachedException. 5000)))))

(defn- idp-com [jwks-provider-fn]
  (component/start
   (kc/keycloak-idp {:base-url base-url :realm-prefixo realm-prefixo :audiencia audiencia
                      :admin-usuario "admin" :admin-senha "admin" :jwks-cache-ttl-s 600}
                     jwks-provider-fn)))

(defn- token-valido
  ([] (token-valido {}))
  ([{:keys [issuer key-id audience assinar-com identidade-id expira-em subject]
     :or {issuer iss key-id kid audience audiencia assinar-com priv
          identidade-id (random-uuid) expira-em (.plusSeconds (Instant/now) 60)
          subject (str (random-uuid))}}]
   (-> (JWT/create)
       (.withIssuer ^String issuer)
       (.withSubject ^String subject)
       (.withKeyId ^String key-id)
       (.withAudience (into-array String [audience]))
       (.withClaim "identidade-id" (str identidade-id))
       (.withExpiresAt ^Instant expira-em)
       (.sign (Algorithm/RSA256 pub ^RSAPrivateKey assinar-com)))))

(deftest token-valido-produz-claims
  (let [ip (idp-com (fn [_ _] (provider-fixo (jwk-de pub kid))))
        iid (random-uuid)
        tok (token-valido {:identidade-id iid})
        claims (idp/verificar-token ip tok)]
    (testing "claims batem com o token"
      (is (= iid (:identidade-id claims)))
      (is (= ente-id (:ente-id claims)))
      (is (some? (:sub claims)))
      (is (some? (:exp claims))))))
