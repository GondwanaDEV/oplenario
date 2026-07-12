(ns oplenario.kernel.components.keycloak-idp
  "Impl REAL do IdentityProvider (§22.5) — Keycloak, realm-por-tenant (Onda D Slice 1, F1.4-carry
  parcialmente fechado: so' o adapter de tenant; operador/gov.br/passkey seguem carry). Verificacao de
  token por JWKS (RS256, issuer-allowlist ANTES de confiar na assinatura — §3.4 do design) +
  provisionamento via admin-API (Task 3). Component com Lifecycle: guarda o cache de JwkProvider
  por-issuer (chaves rotacionam) + o HttpClient do admin-API. NAO importa nenhum modulo (§22.10) — so' o
  protocolo do kernel + libs.

  NAO seto *warn-on-reflection*: o SDK auth0 (java-jwt/jwks-rsa) e' fluent-builder e a interop de
  java.net.http tambem — hint em cada passo deixaria ilegivel (mesmo racional de objeto_store.clj)."
  (:require [clojure.string :as str]
            [com.stuartsierra.component :as component]
            [oplenario.kernel.components.idp :as idp])
  (:import (com.auth0.jwk JwkProviderBuilder JwkException SigningKeyNotFoundException NetworkException RateLimitReachedException)
           (com.auth0.jwt JWT)
           (com.auth0.jwt.algorithms Algorithm)
           (com.auth0.jwt.exceptions JWTVerificationException JWTDecodeException)
           (java.net.http HttpClient)
           (java.security.interfaces RSAPublicKey)
           (java.time Duration)
           (java.util.concurrent TimeUnit)
           (java.util UUID)))

;; ---------------------------------------------------------------------------------------------
;; Allowlist de issuer (§3.4 do design): SO' aceita <base-url>/realms/<realm-prefixo><uuid-valido>,
;; EXATO. Roda ANTES de qualquer verificacao de assinatura — um issuer forjado morre aqui.
;; ---------------------------------------------------------------------------------------------

(defn- uuid-valido? [s]
  (try (UUID/fromString s) true (catch IllegalArgumentException _ false)))

(defn issuer-valido?
  [{:keys [base-url realm-prefixo]} iss]
  (boolean
   (when iss
     (let [prefixo (str base-url "/realms/" realm-prefixo)]
       (and (str/starts-with? iss prefixo)
            (uuid-valido? (subs iss (count prefixo))))))))

(defn ente-id-do-issuer
  "Deriva o ente-id do issuer JA' VALIDADO por `issuer-valido?` — nunca de um claim auto-declarado."
  [{:keys [base-url realm-prefixo]} iss]
  (UUID/fromString (subs iss (count (str base-url "/realms/" realm-prefixo)))))

;; ---------------------------------------------------------------------------------------------
;; JWKS provider por-issuer (cache) — a fabrica real (HTTP) e' injetavel p/ os testes usarem uma fake.
;; ---------------------------------------------------------------------------------------------

(defn- jwks-url [iss] (str iss "/protocol/openid-connect/certs"))

(defn jwks-provider-http
  "Provider REAL (HTTP): builder com cache + rate-limit sobre a URL JWKS do issuer. `JwkProviderBuilder`
  aceita a URL diretamente — SEM a convencao de dominio Auth0 (que apenderia /.well-known/jwks.json);
  confirmado contra um Keycloak 26 real que a URL completa e' usada tal-qual."
  [{:keys [jwks-cache-ttl-s]} iss]
  (-> (JwkProviderBuilder. (java.net.URL. (jwks-url iss)))
      (.cached 10 (Duration/ofSeconds (long jwks-cache-ttl-s)))
      (.rateLimited 10 1 TimeUnit/MINUTES)
      (.build)))

(defn- provider-para!
  "Cache de JwkProvider por-issuer, memoizado no atom do Component (lazy — 1a chamada por issuer constroi)."
  [jwks-cache jwks-provider-fn config iss]
  (or (get @jwks-cache iss)
      (let [p (jwks-provider-fn config iss)]
        (swap! jwks-cache assoc iss p)
        p)))

;; ---------------------------------------------------------------------------------------------
;; verificar-token
;; ---------------------------------------------------------------------------------------------

(defn- claim-str [verificado nome]
  (let [c (.getClaim verificado nome)]
    (when-not (or (nil? c) (.isNull c) (.isMissing c)) (.asString c))))

(defn verificar-token*
  "Nucleo testavel: recebe o mapa {:config :jwks-cache :jwks-provider-fn} (campos do Component) + o
  token. Fail-closed: qualquer problema do PROPRIO token (assinatura/exp/claim/issuer fora da allowlist/
  kid desconhecido NA JWKS publicada) -> nil. Erro de INFRA (JWKS fora do ar/rate-limit) PROPAGA
  (contrato do port, review W2) — a ORDEM dos catches importa: em Java/Clojure `catch` casa por
  subclasse, entao NetworkException (subclasse de SigningKeyNotFoundException no SDK auth0) TEM que ter
  sua propria clausula ANTES da clausula generica de SigningKeyNotFoundException, senao cairia la' e
  viraria nil silenciosamente — mascarando degradacao de infra como 'token invalido'.
  RateLimitReachedException NAO e' subclasse de SigningKeyNotFoundException (estende JwkException
  diretamente) entao sua posicao relativa a essa clausula nao afeta a corretude — mas segue explicita e
  ANTES por clareza/simetria com a outra excecao de infra.
  IllegalArgumentException tambem e' capturada -> nil: um token VALIDAMENTE assinado (issuer+aud+exp+
  assinatura OK) mas com o claim `identidade-id` que nao parseia como UUID (ex.: mapper mal configurado no
  realm) e' problema DO TOKEN, nao de infra — sem essa clausula, `UUID/fromString` lancaria sem ser pego
  por nenhum catch acima e vazaria como excecao nao-tratada (500), violando o mesmo contrato fail-closed
  que as outras clausulas desta funcao existem para cumprir.
  JwkException (generica) tambem e' capturada -> nil, e TEM que vir DEPOIS de SigningKeyNotFoundException
  nesta lista pela mesma razao de subclasse explicada acima: `InvalidPublicKeyException` (lancada por
  `Jwk/.getPublicKey` quando o `kid` do atacante resolve para uma entrada REAL da JWKS cujo tipo de chave
  nao e' RSA, ou esta de outra forma malformada) estende `JwkException` DIRETAMENTE, nao
  `SigningKeyNotFoundException` — sem esta clausula generica ela vazava como excecao nao-tratada (500).
  Como `JwkException` e' superclasse tanto de `SigningKeyNotFoundException`/`NetworkException` quanto de
  `RateLimitReachedException`, colocar esta clausula ANTES delas roubaria as duas clausulas de propagacao
  de infra e as faria virar nil silenciosamente — por isso ela fica POR ULTIMO entre as clausulas de
  JwkException, nunca antes. ClassCastException e' uma segunda linha de defesa: mesmo quando
  `.getPublicKey` retorna com sucesso, se a chave da JWKS nao for RSA o cast implicito do type-hint
  `^RSAPublicKey` no `let` pode lancar em vez de `InvalidPublicKeyException` — mesmo contrato fail-closed,
  mesmo motivo de existir."
  [{:keys [config jwks-cache jwks-provider-fn]} token]
  (try
    (let [nao-verificado (JWT/decode token)
          iss (.getIssuer nao-verificado)]
      (if-not (issuer-valido? config iss)
        nil
        (let [provider   (provider-para! jwks-cache jwks-provider-fn config iss)
              kid        (.getKeyId nao-verificado)
              jwk        (.get provider kid)
              chave-pub  ^RSAPublicKey (.getPublicKey jwk)
              algoritmo  (Algorithm/RSA256 chave-pub nil)
              verificado (-> (JWT/require algoritmo)
                             (.withIssuer (into-array String [iss]))
                             (.withAudience (into-array String [(:audiencia config)]))
                             (.build)
                             (.verify token))
              identidade-id-str (claim-str verificado "identidade-id")]
          {:sub           (.getSubject verificado)
           :identidade-id (when identidade-id-str (UUID/fromString identidade-id-str))
           :ente-id       (ente-id-do-issuer config iss)
           :exp           (some-> verificado .getExpiresAtAsInstant .getEpochSecond int)})))
    (catch NetworkException e (throw e))
    (catch RateLimitReachedException e (throw e))
    (catch SigningKeyNotFoundException _ nil)
    (catch JwkException _ nil)
    (catch JWTVerificationException _ nil)
    (catch JWTDecodeException _ nil)
    (catch IllegalArgumentException _ nil)
    (catch ClassCastException _ nil)))

;; ---------------------------------------------------------------------------------------------
;; Component
;; ---------------------------------------------------------------------------------------------

(defrecord KeycloakIdp [config jwks-provider-fn jwks-cache http-client]
  component/Lifecycle
  (start [this]
    (if http-client
      this
      (assoc this :jwks-cache (atom {}) :http-client (HttpClient/newHttpClient))))
  (stop [this]
    (assoc this :jwks-cache nil :http-client nil))

  idp/IdentityProvider
  (verificar-token [this token] (verificar-token* this token))
  (provisionar-realm! [_ _ente-id] (throw (ex-info "provisionar-realm!: nao implementado nesta task" {})))
  (criar-usuario! [_ _ente-id _usuario] (throw (ex-info "criar-usuario!: nao implementado nesta task" {})))
  (resetar-mfa! [_ _ente-id _identidade-id] (throw (ex-info "resetar-mfa!: nao implementado nesta task" {}))))

(defn keycloak-idp
  "Cria o Component KeycloakIdp (NAO-iniciado — chame component/start). `config` = o mapa `:keycloak` do
  config.edn. `jwks-provider-fn` e' opcional — (fn [config iss] -> JwkProvider); default =
  `jwks-provider-http` (real, HTTP). Testes injetam uma fake sem rede (inversao de dependencia, §5 do
  design)."
  ([config] (keycloak-idp config jwks-provider-http))
  ([config jwks-provider-fn]
   (map->KeycloakIdp {:config config :jwks-provider-fn jwks-provider-fn})))
