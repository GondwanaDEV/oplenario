(ns oplenario.kernel.components.keycloak-idp-test
  "Unit (sem rede): verificar-token contra um par de chaves RSA sintetico + JwkProvider FAKE injetado
  (inversao de dependencia — §5 do design). Cobre a borda de seguranca exaustivamente: assinatura valida/
  invalida, expiracao, confusao de algoritmo, issuer fora da allowlist, kid desconhecido, erro de infra
  PROPAGA (nao vira nil).

  Tambem cobre `provisionar-realm-impl` (Task 7, Onda D Slice 2) — payload/idempotencia do client publico
  `oplenario-web` (PKCE S256), SEM rede/Keycloak vivo (live gated no T18). `admin-token!`/`admin-req!` sao
  privadas (defn-) — redefinidas via `with-redefs-fn` + `#'` (var-quote bypassa a checagem de
  visibilidade; a forma direta `with-redefs` nao compila contra var privada de outro ns, mesma razao pela
  qual `sistema_test.clj` chama `idp-para` via `#'sistema/idp-para`)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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

(defn- provider-com-chave-invalida []
  ;; JWK REAL (nao um mock que lanca) cujo "kty" nao e' RSA/EC — `Jwk/.getPublicKey` lanca
  ;; `InvalidPublicKeyException` (subclasse DIRETA de `JwkException`, nao de `SigningKeyNotFoundException`)
  ;; quando o kid do atacante resolve p/ uma entrada REAL da JWKS cujo tipo de chave nao e' suportado.
  (reify JwkProvider
    (get [_ _kid] (Jwk/fromValues {"kty" "oct" "kid" kid "use" "sig" "alg" "RS256"}))))

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

(deftest assinatura-de-outra-chave-invalida
  (let [ip (idp-com (fn [_ _] (provider-fixo (jwk-de pub kid)))) ; JWKS publica a chave "pub"...
        outra-priv ^RSAPrivateKey (.getPrivate (gerar-par-rsa))
        tok (-> (JWT/create) (.withIssuer ^String iss) (.withKeyId ^String kid)
                (.withAudience (into-array String [audiencia])) (.withClaim "identidade-id" (str (random-uuid)))
                (.withExpiresAt (.plusSeconds (Instant/now) 60))
                (.sign (Algorithm/RSA256 pub outra-priv)))] ; ...mas o token e' assinado por OUTRA privada
    (is (nil? (idp/verificar-token ip tok)) "assinatura que nao bate com a chave publicada -> nil")))

(deftest token-expirado-invalido
  (let [ip (idp-com (fn [_ _] (provider-fixo (jwk-de pub kid))))
        tok (token-valido {:expira-em (.minusSeconds (Instant/now) 60)})]
    (is (nil? (idp/verificar-token ip tok)) "exp no passado -> nil")))

(deftest alg-none-invalido
  (let [ip (idp-com (fn [_ _] (provider-fixo (jwk-de pub kid))))
        tok (-> (JWT/create) (.withIssuer ^String iss) (.withKeyId ^String kid)
                (.withAudience (into-array String [audiencia]))
                (.withExpiresAt (.plusSeconds (Instant/now) 60))
                (.sign (Algorithm/none)))]
    (is (nil? (idp/verificar-token ip tok)) "alg:none nunca bate com o RS256 exigido -> nil")))

(deftest confusao-de-algoritmo-hs256-invalida
  (let [ip (idp-com (fn [_ _] (provider-fixo (jwk-de pub kid))))
        ;; ataque classico: assina HS256 usando os bytes da chave PUBLICA como segredo HMAC.
        segredo (.getEncoded pub)
        tok (-> (JWT/create) (.withIssuer ^String iss) (.withKeyId ^String kid)
                (.withAudience (into-array String [audiencia]))
                (.withExpiresAt (.plusSeconds (Instant/now) 60))
                (.sign (Algorithm/HMAC256 ^bytes segredo)))]
    (is (nil? (idp/verificar-token ip tok)) "confusao RS256<->HS256 -> nil (algoritmo do verifier e' sempre RS256)")))

(deftest issuer-fora-da-allowlist-invalido
  (let [ip (idp-com (fn [_ _] (provider-fixo (jwk-de pub kid))))
        tok (token-valido {:issuer "http://evil.example.com/realms/ente-forjado"})]
    (is (nil? (idp/verificar-token ip tok)) "issuer fora do padrao base-url/realm-prefixo -> nil, ANTES de tocar JWKS")))

(deftest issuer-com-uuid-invalido-na-allowlist
  (let [ip (idp-com (fn [_ _] (provider-fixo (jwk-de pub kid))))
        tok (token-valido {:issuer (str base-url "/realms/" realm-prefixo "nao-e-uuid")})]
    (is (nil? (idp/verificar-token ip tok)) "sufixo pos-prefixo que nao e' UUID valido -> nil")))

(deftest audiencia-errada-invalida
  (let [ip (idp-com (fn [_ _] (provider-fixo (jwk-de pub kid))))
        tok (token-valido {:audience "outro-client"})]
    (is (nil? (idp/verificar-token ip tok)) "aud diferente do configurado -> nil")))

(deftest kid-desconhecido-na-jwks-e-invalido
  (let [ip (idp-com (fn [_ _] (provider-sem-chave)))
        tok (token-valido {})]
    (is (nil? (idp/verificar-token ip tok)) "kid nao encontrado na JWKS publicada -> nil (token invalido)")))

(deftest erro-de-rede-na-jwks-propaga
  (let [ip (idp-com (fn [_ _] (provider-com-erro-de-rede)))
        tok (token-valido {})]
    (is (thrown? NetworkException (idp/verificar-token ip tok))
        "falha de INFRA (JWKS fora do ar) PROPAGA — nunca vira nil (contrato do port, review W2)")))

(deftest rate-limit-na-jwks-propaga
  (let [ip (idp-com (fn [_ _] (provider-rate-limited)))
        tok (token-valido {})]
    (is (thrown? RateLimitReachedException (idp/verificar-token ip tok))
        "rate-limit tambem e' degradacao de infra, nao 'token invalido'")))

(deftest kid-aponta-para-chave-nao-rsa-invalido
  (let [ip (idp-com (fn [_ _] (provider-com-chave-invalida)))
        tok (token-valido {})]
    (is (nil? (idp/verificar-token ip tok))
        "kid resolve p/ entrada REAL da JWKS cujo tipo de chave nao e' RSA (InvalidPublicKeyException,
        subclasse direta de JwkException) -> nil, nao excecao nao-tratada (500)")))

(deftest token-malformado-invalido
  (let [ip (idp-com (fn [_ _] (provider-fixo (jwk-de pub kid))))]
    (is (nil? (idp/verificar-token ip "isto-nao-e-um-jwt")) "token que nao decodifica -> nil, sem lancar")))

(deftest identidade-id-ausente-fica-nil
  (let [ip (idp-com (fn [_ _] (provider-fixo (jwk-de pub kid))))
        tok (-> (JWT/create) (.withIssuer ^String iss) (.withKeyId ^String kid)
                (.withAudience (into-array String [audiencia]))
                (.withExpiresAt (.plusSeconds (Instant/now) 60))
                (.sign (Algorithm/RSA256 pub ^RSAPrivateKey priv)))]
    (is (nil? (:identidade-id (idp/verificar-token ip tok)))
        "claim identidade-id ausente -> :identidade-id nil no mapa de claims (nao lanca; resolver-sessao ja' trata nil)")))

(deftest identidade-id-malformado-invalido
  (let [ip (idp-com (fn [_ _] (provider-fixo (jwk-de pub kid))))
        ;; token VALIDAMENTE assinado (issuer/aud/exp/assinatura todos OK) mas o claim identidade-id nao
        ;; parseia como UUID (ex.: mapper mal configurado no realm) — achado desta revisao: sem catch de
        ;; IllegalArgumentException isso vazava como excecao nao-tratada (500) em vez de nil (401),
        ;; violando o mesmo contrato fail-closed que as outras clausulas do catch existem para cumprir.
        tok (-> (JWT/create) (.withIssuer ^String iss) (.withKeyId ^String kid)
                (.withAudience (into-array String [audiencia]))
                (.withClaim "identidade-id" "nao-e-um-uuid")
                (.withExpiresAt (.plusSeconds (Instant/now) 60))
                (.sign (Algorithm/RSA256 pub ^RSAPrivateKey priv)))]
    (is (nil? (idp/verificar-token ip tok))
        "claim identidade-id que nao parseia como UUID -> nil, nao excecao nao-tratada")))

;; ---------------------------------------------------------------------------------------------
;; provisionar-realm-impl — client publico oplenario-web (PKCE S256), Task 7
;; ---------------------------------------------------------------------------------------------

(def ^:private config-provisionamento
  {:base-url base-url :realm-prefixo realm-prefixo :audiencia audiencia
   :admin-usuario "admin" :admin-senha "admin" :jwks-cache-ttl-s 600
   :web-client-id "oplenario-web"
   :redirect-uris ["http://localhost:3000/api/auth/callback"]
   :web-origins ["http://localhost:3000"]})

(defn- fake-admin-req!
  "Fake de `admin-req!` — captura toda chamada em `chamadas` (atom, vetor de {:metodo :caminho :corpo}) e
  responde por convencao: GET raiz do realm (`/admin/realms/<realm>`, sem sufixo) -> 200 (realm ja
  existe, pula a criacao); GET .../users/profile -> 200 com identidade-id JA declarado (pula o PUT); GET
  .../clients?clientId=X -> 200 com [] (nao existe) ou [{...}] (existe), conforme `clients-existentes`
  (set de clientId); POST/PUT -> sucesso (201/200)."
  [chamadas clients-existentes]
  (fn [_http-client _token metodo caminho corpo-map _base-url]
    (swap! chamadas conj {:metodo metodo :caminho caminho :corpo corpo-map})
    (cond
      (str/ends-with? caminho "/users/profile")
      {:status 200 :corpo {:attributes [{:name "identidade-id"}]}}

      (re-matches #"/admin/realms/[^/]+" caminho)
      {:status 200 :corpo {}}

      (str/includes? caminho "/clients?clientId=")
      (let [client-id (subs caminho (+ (str/index-of caminho "clientId=") (count "clientId=")))]
        (if (contains? clients-existentes client-id)
          {:status 200 :corpo [{:id "existing-id" :clientId client-id}]}
          {:status 200 :corpo []}))

      (= metodo :post) {:status 201 :corpo {}}
      (= metodo :put)  {:status 200 :corpo {}}
      :else            {:status 200 :corpo {}})))

(defn- provisionar-capturando!
  "Roda `provisionar-realm-impl` (privada) com `admin-token!`/`admin-req!` fakes, devolve o vetor de
  chamadas capturadas ao `admin-req!`."
  [clients-existentes]
  (let [chamadas (atom [])]
    (with-redefs-fn {#'kc/admin-token! (fn [_config _http-client] "fake-token")
                      #'kc/admin-req!   (fake-admin-req! chamadas clients-existentes)}
      (fn [] (#'kc/provisionar-realm-impl {:config config-provisionamento :http-client nil} ente-id)))
    @chamadas))

(defn- post-do-client [chamadas client-id]
  (some #(when (and (= :post (:metodo %)) (= client-id (:clientId (:corpo %)))) %) chamadas))

(deftest provisionar-realm-cria-client-web-publico-pkce
  (let [chamadas (provisionar-capturando! #{})
        post-web (post-do-client chamadas "oplenario-web")]
    (testing "POST de criacao do client oplenario-web com payload PKCE correto"
      (is (some? post-web) "esperava um POST de client com clientId oplenario-web")
      (is (true? (:publicClient (:corpo post-web))))
      (is (true? (:standardFlowEnabled (:corpo post-web))))
      (is (false? (:directAccessGrantsEnabled (:corpo post-web))))
      (is (= "S256" (get-in post-web [:corpo :attributes "pkce.code.challenge.method"])))
      (is (seq (:redirectUris (:corpo post-web))) "redirectUris nao-vazio"))))

(deftest provisionar-realm-idempotente-nao-recria-client-web
  (let [chamadas (provisionar-capturando! #{"oplenario-web" "oplenario-backend"})]
    (testing "client oplenario-web ja existe -> nenhum POST de criacao"
      (is (nil? (post-do-client chamadas "oplenario-web"))))))
