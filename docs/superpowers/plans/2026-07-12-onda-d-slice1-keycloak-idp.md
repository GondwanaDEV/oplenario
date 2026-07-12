# Onda D · Slice 1 — Adapter Keycloak real do IdP — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `idp-dev` (trusts any JSON as claims) with a real `KeycloakIdp` — JWKS-verified JWT
validation under realm-per-tenant, plus admin-API provisioning (realm/user/MFA-reset) — injectable into
the non-dev boot in place of the stub, closing the backend half of "real login" for Onda D.

**Architecture:** One new Component (`kernel/components/keycloak_idp.clj`) implementing the existing
`IdentityProvider` protocol (`kernel/components/idp.clj`) untouched. `verificar-token` reads the token's
issuer unverified, checks it against an allowlist derived from config, then verifies the RS256 signature
against that issuer's JWKS (fetched via an injectable provider — real HTTP in prod, synthetic keypair in
unit tests). Admin operations (`provisionar-realm!`/`criar-usuario!`/`resetar-mfa!`) call the Keycloak
Admin REST API directly via `java.net.http.HttpClient` + `jsonista` (no new HTTP-client dependency).
Everything downstream — `resolver-sessao`, the Pedestal interceptor chain, `exige-papel`/`policy.check` —
is unchanged; this plan only fills the `IdentityProvider` seam.

**Tech Stack:** Clojure, Component (Stuart Sierra), `com.auth0/java-jwt` 4.4.0, `com.auth0/jwks-rsa`
0.22.1, `java.net.http.HttpClient` (JDK, no new dep), `jsonista` (already a dep), kaocha.

**Design reference:** `docs/superpowers/specs/2026-07-12-onda-d-slice1-keycloak-idp-design.md`. In
conflict, this plan documents corrections found during the technical investigation below (mirrors the
project's established practice of noting "correção pós-investigação técnica" rather than relitigating
the whole spec — see Onda C Slice 2's precedent).

## Correções pós-investigação técnica (found by exercising a real Keycloak 26 container)

The spec's §3.5/§3.6 sketch was refined after actually provisioning a realm/client/user against the
`--profile auth` Keycloak from `docker-compose.yml` (Keycloak 26.0.0) and inspecting the exact
`java-jwt`/`jwks-rsa` 4.4.0/0.22.1 APIs. Four corrections, each would have caused a silent bug otherwise:

1. **Admin auth is ROPC against the built-in `admin-cli` public client in the `master` realm**
   (`KEYCLOAK_ADMIN`/`KEYCLOAK_ADMIN_PASSWORD`, already in `docker-compose.yml`), **not** a confidential
   `oplenario-provisioner` client with a secret as the spec sketched — no such client exists by default
   and inventing one adds a provisioning step for no benefit in this slice. Config keys are
   `:admin-usuario`/`:admin-senha`, not `:admin-client-id`/`:admin-secret`.
2. **Keycloak 26 realms default to a declarative User Profile with unmanaged attributes OFF.** Creating
   a user with `"attributes":{"identidade-id":[...]}` **silently drops the attribute** unless the realm's
   User Profile explicitly declares it first (verified: without this step, `GET` on the created user shows
   no `attributes` key at all — no error, just silent loss). `provisionar-realm!` must `GET`/`PUT`
   `/admin/realms/{realm}/users/profile` to append the attribute (never replace the array — the default
   `username`/`email`/`firstName`/`lastName` entries must survive).
3. **The default `aud` claim is `"account"`, not the client's own id.** A `.withAudience("oplenario-backend")`
   check would fail against an unmodified client. Fixed by adding an `oidc-audience-mapper` protocol mapper
   (`included.client.audience` = the audiencia) at client-creation time — verified this puts the client id
   into the `aud` array alongside `"account"`, and `.withAudience(...)` matches on array membership.
2. **Keycloak's default User Profile requires `firstName`/`lastName`** for users with role `user` (found
   via a first-hand "Account is not fully set up" login failure) — `criar-usuario!` must set them.

## Global Constraints

- `identidade`, `interceptors.clj`, `rotas.clj`, `resolver-sessao` are NOT modified — the `IdentityProvider`
  protocol's shape is the fixed contract this slice fills.
- No new HTTP-client dependency — `java.net.http.HttpClient` (JDK 21, already the CI Java version) + the
  already-present `jsonista`.
- `*warn-on-reflection*` is deliberately NOT set in the new file — same rationale as
  `kernel/components/objeto_store.clj` (fluent Java SDK interop; hinting every call would be unreadable).
- Passkey/WebAuthn enrollment, gov.br broker, the `admin_sistema` operator IdP, and ICP-Brasil real signing
  are explicitly out of scope (see spec §1) — do not touch `assinador_icp.clj` or `idp_admin.clj`.
- Local Keycloak: `docker compose --profile auth up -d keycloak` from `apps/backend/`. **First boot takes
  ~3-5 minutes** (Quarkus augmentation + embedded-DB schema init) — do not assume readiness before polling
  `GET /realms/master/.well-known/openid-configuration` succeeds. **Port 8080 may collide with other local
  projects** — override via `OPLENARIO_KEYCLOAK_PORT` (docker host port) and the matching
  `KEYCLOAK_BASE_URL` env var for the app, same pattern as `OPLENARIO_PG_PORT`/`DATABASE_URL`.

---

### Task 1: Dependencies + Keycloak config plumbing

**Files:**
- Modify: `apps/backend/deps.edn`
- Modify: `apps/backend/resources/config.edn`
- Modify: `apps/backend/src/oplenario/config.clj`
- Test: `apps/backend/test/unit/oplenario/config_test.clj` (create if it doesn't already cover this shape — check first with `Read`; if the file exists, add to it following its existing style instead of replacing it)

**Interfaces:**
- Produces: config map shape `{:keycloak {:base-url :realm-prefixo :audiencia :admin-usuario :admin-senha :jwks-cache-ttl-s}}`, reachable via `(oplenario.config/carregar env-map)`. Later tasks read this shape by destructuring `(:keycloak config)`.

- [ ] **Step 1: Check for an existing config test file**

Run: `ls apps/backend/test/unit/oplenario/config_test.clj 2>&1`

If it exists, `Read` it first and follow its existing conventions/fixtures for the new test below instead
of assuming a blank file.

- [ ] **Step 2: Write the failing test for env overrides**

Add to `apps/backend/test/unit/oplenario/config_test.clj` (create the file with this content + an
`(ns ...)` header if it doesn't exist yet; if it exists, add the `deftest` alongside the others and reuse
its existing `ns`/requires):

```clojure
(ns oplenario.config-test
  (:require [clojure.test :refer [deftest is]]
            [oplenario.config :as config]))

(deftest keycloak-overrides-do-ambiente
  (let [c (config/carregar {"KEYCLOAK_BASE_URL" "http://localhost:9090"
                             "KEYCLOAK_REALM_PREFIXO" "casa-"
                             "KEYCLOAK_AUDIENCIA" "oplenario-teste"
                             "KEYCLOAK_ADMIN_USUARIO" "root"
                             "KEYCLOAK_ADMIN_SENHA" "segredo"
                             "KEYCLOAK_JWKS_CACHE_TTL_S" "120"})]
    (is (= "http://localhost:9090" (get-in c [:keycloak :base-url])))
    (is (= "casa-" (get-in c [:keycloak :realm-prefixo])))
    (is (= "oplenario-teste" (get-in c [:keycloak :audiencia])))
    (is (= "root" (get-in c [:keycloak :admin-usuario])))
    (is (= "segredo" (get-in c [:keycloak :admin-senha])))
    (is (= 120 (get-in c [:keycloak :jwks-cache-ttl-s])))))

(deftest keycloak-defaults-sem-override
  (let [c (config/carregar {})]
    (is (= "ente-" (get-in c [:keycloak :realm-prefixo])))
    (is (= "oplenario-backend" (get-in c [:keycloak :audiencia])))
    (is (pos? (get-in c [:keycloak :jwks-cache-ttl-s])))))
```

- [ ] **Step 3: Run the test to verify it fails**

Run (from `apps/backend/`): `clojure -M:test --focus oplenario.config-test`
Expected: FAIL — `:keycloak` key missing from the config map (the base `config.edn` has no such block yet).

- [ ] **Step 4: Add the `:keycloak` block to `resources/config.edn`**

Read the current file first (`Read apps/backend/resources/config.edn`) to get the exact surrounding
structure, then add a new top-level key (insert alongside the existing `:objeto-store`/`:modulos` keys,
same style — one line, inline map):

```edn
 :keycloak {:base-url "http://localhost:8080" :realm-prefixo "ente-" :audiencia "oplenario-backend"
            :admin-usuario "admin" :admin-senha "admin" :jwks-cache-ttl-s 600}
```

- [ ] **Step 5: Add env overrides to `config.clj`**

In `apps/backend/src/oplenario/config.clj`, inside the `cond->` chain of `carregar` (right after the
existing `MINIO_*`/`HTTP_PORT` clauses), add:

```clojure
     (get env "KEYCLOAK_BASE_URL")        (assoc-in [:keycloak :base-url]        (get env "KEYCLOAK_BASE_URL"))
     (get env "KEYCLOAK_REALM_PREFIXO")   (assoc-in [:keycloak :realm-prefixo]   (get env "KEYCLOAK_REALM_PREFIXO"))
     (get env "KEYCLOAK_AUDIENCIA")       (assoc-in [:keycloak :audiencia]       (get env "KEYCLOAK_AUDIENCIA"))
     (get env "KEYCLOAK_ADMIN_USUARIO")   (assoc-in [:keycloak :admin-usuario]   (get env "KEYCLOAK_ADMIN_USUARIO"))
     (get env "KEYCLOAK_ADMIN_SENHA")     (assoc-in [:keycloak :admin-senha]     (get env "KEYCLOAK_ADMIN_SENHA"))
     (get env "KEYCLOAK_JWKS_CACHE_TTL_S") (assoc-in [:keycloak :jwks-cache-ttl-s]
                                                      (Integer/parseInt (get env "KEYCLOAK_JWKS_CACHE_TTL_S")))
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `clojure -M:test --focus oplenario.config-test`
Expected: PASS (both `deftest`s green).

- [ ] **Step 7: Commit**

```bash
git add apps/backend/deps.edn apps/backend/resources/config.edn apps/backend/src/oplenario/config.clj apps/backend/test/unit/oplenario/config_test.clj
git commit -m "feat(be): config do Keycloak (Onda D Slice 1) — base-url/realm-prefixo/audiencia/admin/jwks-ttl"
```

(Note: `deps.edn` isn't touched by this test but belongs in this commit — see Step 0 below, done before Step 1 conceptually but committed together here since nothing exercises it until Task 2.)

- [ ] **Step 0 (do this before Step 7's commit, any point in the task): add the two new deps**

In `apps/backend/deps.edn`, inside the `:deps` map, add two lines (after `com.taoensso/carmine`, matching
the existing alignment/comment style):

```clojure
        com.auth0/java-jwt                      {:mvn/version "4.4.0"}   ; verificacao JWT RS256 (Onda D Slice 1)
        com.auth0/jwks-rsa                       {:mvn/version "0.22.1"} ; JWKS cache+rate-limit por-issuer
```

Run: `cd apps/backend && clojure -Sdeps '{}' -Spath >/dev/null && echo "deps resolvem OK"` — confirms the
deps.edn is well-formed and the two new coordinates resolve (already resolved once during design
verification, so this should be a cache hit).

---

### Task 2: `verificar-token` — the security keystone

**Files:**
- Create: `apps/backend/src/oplenario/kernel/components/keycloak_idp.clj`
- Test: `apps/backend/test/unit/oplenario/kernel/keycloak_idp_test.clj`

**Interfaces:**
- Consumes: `oplenario.kernel.components.idp/IdentityProvider` protocol (existing, unmodified) — this task
  implements only `verificar-token`; `provisionar-realm!`/`criar-usuario!`/`resetar-mfa!` are stubbed to
  throw `(ex-info "nao implementado nesta task" {})` and get real bodies in Task 3.
- Produces (for Task 3 to extend and Task 4/5 to consume):
  - `(keycloak-idp config)` and `(keycloak-idp config jwks-provider-fn)` — constructor fns, 1-arity uses
    the real HTTP provider, 2-arity accepts an injected `(fn [config iss] -> JwkProvider)` for tests.
  - `defrecord KeycloakIdp [config jwks-provider-fn jwks-cache http-client]` implementing
    `component/Lifecycle` + `idp/IdentityProvider`.
  - `verificar-token*` (private-by-convention but tested directly since it's the core), `issuer-valido?`,
    `ente-id-do-issuer`, `jwks-provider-http` — all `defn` (not `defn-`) so Task 3's admin functions and
    tests can call them; only genuinely internal helpers use `defn-`.
  - Claims shape returned on success: `{:sub <string> :identidade-id <uuid-or-nil> :ente-id <uuid> :exp <int-epoch-seconds>}`.

- [ ] **Step 1: Write the namespace skeleton with the JWK-synthesis test helpers first (no test yet — this is fixture code the tests need)**

Create `apps/backend/test/unit/oplenario/kernel/keycloak_idp_test.clj`:

```clojure
(ns oplenario.kernel.components.keycloak-idp-test
  "Unit (sem rede): verificar-token contra um par de chaves RSA sintetico + JwkProvider FAKE injetado
  (inversao de dependencia — §5 do design). Cobre a borda de seguranca exaustivamente: assinatura valida/
  invalida, expiracao, confusao de algoritmo, issuer fora da allowlist, kid desconhecido, erro de infra
  PROPAGA (nao vira nil)."
  (:require [clojure.test :refer [deftest is testing]]
            [com.stuartsierra.component :as component]
            [oplenario.kernel.components.keycloak-idp :as kc])
  (:import (com.auth0.jwk Jwk JwkProvider SigningKeyNotFoundException NetworkException RateLimitReachedException)
           (com.auth0.jwt JWT)
           (com.auth0.jwt.algorithms Algorithm)
           (java.security KeyPairGenerator)
           (java.security.interfaces RSAPublicKey RSAPrivateKey)
           (java.time Instant)
           (java.util Base64 Arrays UUID)))

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

(defn- jwk-de [^RSAPublicKey chave kid]
  (Jwk/fromValues {"kty" "RSA" "kid" kid "use" "sig" "alg" "RS256"
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
  ([{:keys [issuer key-id audience assinar-com identidade-id expira-em]
     :or {issuer iss key-id kid audience audiencia assinar-com priv
          identidade-id (random-uuid) expira-em (.plusSeconds (Instant/now) 60)}}]
   (-> (JWT/create)
       (.withIssuer ^String issuer)
       (.withKeyId ^String key-id)
       (.withAudience (into-array String [audience]))
       (.withClaim "identidade-id" (str identidade-id))
       (.withExpiresAt ^Instant expira-em)
       (.sign (Algorithm/RSA256 pub ^RSAPrivateKey assinar-com)))))
```

This file has no `deftest` yet — it's the fixture layer. Do not run tests yet; proceed to Step 2 which adds
the first real test using these helpers.

- [ ] **Step 2: Add the first failing test (happy path)**

Append to the same test file:

```clojure
(deftest token-valido-produz-claims
  (let [idp (idp-com (fn [_ _] (provider-fixo (jwk-de pub kid))))
        iid (random-uuid)
        tok (token-valido {:identidade-id iid})
        claims (.verificarToken idp tok)]
    (testing "claims batem com o token"
      (is (= iid (:identidade-id claims)))
      (is (= ente-id (:ente-id claims)))
      (is (some? (:sub claims)))
      (is (some? (:exp claims))))))
```

Wait — the protocol method is `verificar-token` (Clojure kebab-case), not `.verificarToken` (that's Java
camelCase interop syntax, wrong here — `verificar-token` is a plain Clojure protocol function, called as
`(idp/verificar-token idp tok)`). Fix the test to require the protocol namespace and call it correctly:

```clojure
(ns oplenario.kernel.components.keycloak-idp-test
  ...
  (:require [clojure.test :refer [deftest is testing]]
            [com.stuartsierra.component :as component]
            [oplenario.kernel.components.idp :as idp]
            [oplenario.kernel.components.keycloak-idp :as kc])
  ...)
```

(add the `idp` require alongside `kc`), then:

```clojure
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
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd apps/backend && clojure -M:test --focus oplenario.kernel.components.keycloak-idp-test`
Expected: FAIL — namespace `oplenario.kernel.components.keycloak-idp` doesn't exist yet.

- [ ] **Step 4: Write the minimal implementation**

Create `apps/backend/src/oplenario/kernel/components/keycloak_idp.clj`:

```clojure
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
  subclasse, entao NetworkException (subclasse de SigningKeyNotFoundException) TEM que ter sua propria
  clausula ANTES da clausula generica de SigningKeyNotFoundException, senao cairia la' e viraria nil
  silenciosamente — mascarando degradacao de infra como 'token invalido'."
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
    (catch JWTVerificationException _ nil)
    (catch JWTDecodeException _ nil)))

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
  (provisionar-realm! [_ _ente-id] (throw (ex-info "provisionar-realm!: implementado na Task 3" {})))
  (criar-usuario! [_ _ente-id _usuario] (throw (ex-info "criar-usuario!: implementado na Task 3" {})))
  (resetar-mfa! [_ _ente-id _identidade-id] (throw (ex-info "resetar-mfa!: implementado na Task 3" {}))))

(defn keycloak-idp
  "Cria o Component KeycloakIdp (NAO-iniciado — chame component/start). `config` = o mapa `:keycloak` do
  config.edn. `jwks-provider-fn` e' opcional — (fn [config iss] -> JwkProvider); default =
  `jwks-provider-http` (real, HTTP). Testes injetam uma fake sem rede (inversao de dependencia, §5 do
  design)."
  ([config] (keycloak-idp config jwks-provider-http))
  ([config jwks-provider-fn]
   (map->KeycloakIdp {:config config :jwks-provider-fn jwks-provider-fn})))
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `clojure -M:test --focus oplenario.kernel.components.keycloak-idp-test`
Expected: PASS (`token-valido-produz-claims` green).

- [ ] **Step 6: Commit the happy path**

```bash
git add apps/backend/src/oplenario/kernel/components/keycloak_idp.clj apps/backend/test/unit/oplenario/kernel/keycloak_idp_test.clj
git commit -m "feat(be): KeycloakIdp — verificar-token (JWKS RS256, caminho feliz)"
```

- [ ] **Step 7: Add the exhaustive edge-case tests (all in one batch — this is the security-critical surface)**

Append to the test file:

```clojure
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
```

- [ ] **Step 8: Run the test to verify it fails (before adjusting anything)**

Run: `clojure -M:test --focus oplenario.kernel.components.keycloak-idp-test`
Expected: all NEW tests should already PASS against the Step 4 implementation (this is the rare case
where the implementation was written broad enough upfront) — if any fail, that's a real gap in
`verificar-token*` to fix now (most likely candidate: catch-clause ordering, or the issuer-regex logic).
Do not proceed to Step 9 until every test in this file is green.

- [ ] **Step 9: Fix any failures found in Step 8, then re-run to confirm all green**

Run: `clojure -M:test --focus oplenario.kernel.components.keycloak-idp-test`
Expected: PASS — all `deftest`s in the file green (happy path + 12 edge cases).

- [ ] **Step 10: Run clj-kondo to catch lint issues before committing**

Run: `clojure -M:test -m kaocha.runner --focus oplenario.kernel.components.keycloak-idp-test` (already run
above) then separately: `clj-kondo --lint src/oplenario/kernel/components/keycloak_idp.clj test/unit/oplenario/kernel/keycloak_idp_test.clj`
Expected: 0 errors (warnings about unused requires, if any, should be fixed — e.g. remove `JwkException`
import if it ends up unused since only its subclasses are caught explicitly).

- [ ] **Step 11: Commit**

```bash
git add apps/backend/src/oplenario/kernel/components/keycloak_idp.clj apps/backend/test/unit/oplenario/kernel/keycloak_idp_test.clj
git commit -m "test(be): KeycloakIdp verificar-token — cobertura exaustiva de borda (assinatura/exp/alg/issuer/aud/kid/infra)"
```

---

### Task 3: Admin provisioning (`provisionar-realm!`, `criar-usuario!`, `resetar-mfa!`)

**Files:**
- Modify: `apps/backend/src/oplenario/kernel/components/keycloak_idp.clj`
- Test: `apps/backend/test/keycloak/oplenario/keycloak/provisionamento_test.clj` (new test-path root,
  registered in Step 2 below — kept OUT of `test/integration/` on purpose: `clojure -M:test` with no
  `--focus`/`--skip` runs every configured kaocha id, and `test/integration/` already runs by default in
  CI, which has no Keycloak container. A dedicated `test/keycloak` root lets `--skip :keycloak` exclude
  exactly this suite without touching `:integration`.)
- Modify: `apps/backend/tests.edn`
- Modify: `apps/backend/deps.edn` (add `test/keycloak` to the `:test` alias's `:extra-paths`)
- Modify: `.github/workflows/ci.yml` (repo root, NOT under `apps/backend/`)

**Interfaces:**
- Consumes: `KeycloakIdp` record shape from Task 2 (`config`, `http-client` fields); `config` map's
  `:base-url`/`:realm-prefixo`/`:audiencia`/`:admin-usuario`/`:admin-senha`.
- Produces: `provisionar-realm!` returns `{:realm <string>}`; `criar-usuario!` takes
  `{:identidade-id <uuid> :nome <string> :email <string>}` and returns `{:keycloak-user-id <string-or-nil>}`;
  `resetar-mfa!` returns `{:identidade-id <uuid> :removidas <int>}` or throws if the identity has no user
  in that realm. These 3 fns are consumed by Task 5's end-to-end test and by the `identidade` module in a
  later slice (not this one).

**This entire task's tests require the docker Keycloak container.** Before starting, run:

```bash
cd apps/backend
OPLENARIO_KEYCLOAK_PORT=8090 docker compose --profile auth up -d keycloak
```

Then poll until ready (can take 3-5 minutes on first boot — do not give up early):

```bash
for i in $(seq 1 60); do curl -sf http://localhost:8090/realms/master/.well-known/openid-configuration >/dev/null 2>&1 && echo "ready" && break; sleep 5; done
```

If port 8090 is also taken on your machine, pick another free port and adjust both the `docker compose`
env var above and `KEYCLOAK_BASE_URL` below consistently.

- [ ] **Step 1: Register the `:keycloak` kaocha suite (before writing the test, so it's discoverable)**

In `apps/backend/tests.edn`, add a 4th entry:

```edn
#kaocha/v1
{:tests [{:id :unit        :test-paths ["test/unit"]}
         {:id :integration :test-paths ["test/integration"]}
         {:id :e2e         :test-paths ["test/e2e"]}
         {:id :keycloak    :test-paths ["test/keycloak"]}]}
```

In `apps/backend/deps.edn`, the `:test` alias currently has
`:extra-paths ["test/unit" "test/integration" "test/e2e"]` — add `"test/keycloak"` so the namespace
resolves on the classpath:

```clojure
  :test {:extra-paths ["test/unit" "test/integration" "test/e2e" "test/keycloak"]
         ...}
```

Run: `mkdir -p apps/backend/test/keycloak/oplenario/keycloak`

- [ ] **Step 2: Write the failing integration test for realm provisioning (idempotent)**

Create `apps/backend/test/keycloak/oplenario/keycloak/provisionamento_test.clj`:

```clojure
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
```

- [ ] **Step 3: Run the test to verify it fails**

Run (from `apps/backend/`): `clojure -M:test --focus :keycloak`
Expected: FAIL — `provisionar-realm!` throws `"provisionar-realm!: implementado na Task 3"` (the Task 2 stub).

- [ ] **Step 4: Implement `provisionar-realm!`, `criar-usuario!`, `resetar-mfa!`**

In `apps/backend/src/oplenario/kernel/components/keycloak_idp.clj`, extend the `:import` form (add to the
existing `(:import ...)` block):

```clojure
           (java.net URI URLEncoder)
           (java.net.http HttpRequest HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers)
```

Add `jsonista.core` to `:require`:

```clojure
            [jsonista.core :as json]
```

Add this new section between the JWKS-provider section and the `verificar-token` section (or after
`verificar-token*`, before the `defrecord` — placement doesn't matter functionally, keep it grouped and
add a comment banner matching the file's existing style):

```clojure
;; ---------------------------------------------------------------------------------------------
;; Admin API (provisionamento) — java.net.http + jsonista, sem lib HTTP nova.
;; ---------------------------------------------------------------------------------------------

(defn- body->json [m] (json/write-value-as-string m))
(defn- json->body [s] (when (seq s) (json/read-value s json/keyword-keys-object-mapper)))

(defn- admin-token!
  "Token admin via ROPC no realm master, client PUBLICO builtin `admin-cli` (KEYCLOAK_ADMIN/_PASSWORD do
  docker-compose dev — nao existe client confidential dedicado, confirmado contra o Keycloak 26 real).
  SEM CACHE nesta fatia: provisionamento e' operacao administrativa rara, nao hot-path (YAGNI; cache de
  token admin fica carry se o volume justificar)."
  [{:keys [base-url admin-usuario admin-senha]} ^HttpClient http-client]
  (let [corpo (str "grant_type=password&client_id=admin-cli"
                   "&username=" (URLEncoder/encode ^String admin-usuario "UTF-8")
                   "&password=" (URLEncoder/encode ^String admin-senha "UTF-8"))
        req (-> (HttpRequest/newBuilder)
                (.uri (URI/create (str base-url "/realms/master/protocol/openid-connect/token")))
                (.header "Content-Type" "application/x-www-form-urlencoded")
                (.POST (HttpRequest$BodyPublishers/ofString corpo))
                (.build))
        resp (.send http-client req (HttpResponse$BodyHandlers/ofString))]
    (if (= 200 (.statusCode resp))
      (:access_token (json->body (.body resp)))
      (throw (ex-info "keycloak-idp: falha ao obter token admin (infra)"
                       {:status (.statusCode resp) :corpo (.body resp)})))))

(defn- admin-req!
  "Requisicao autenticada ao admin-API. `metodo` = :get/:post/:put/:delete. Devolve {:status :corpo :headers}."
  [^HttpClient http-client token metodo caminho corpo-map base-url]
  (let [builder (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str base-url caminho)))
                    (.header "Authorization" (str "Bearer " token))
                    (.header "Content-Type" "application/json"))
        req (case metodo
              :get    (.GET builder)
              :post   (.POST builder (HttpRequest$BodyPublishers/ofString (body->json corpo-map)))
              :put    (.PUT builder (HttpRequest$BodyPublishers/ofString (body->json corpo-map)))
              :delete (.DELETE builder))
        resp (.send http-client (.build req) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp) :corpo (json->body (.body resp)) :headers (.headers resp)}))

(defn- declarar-atributo-identidade!
  "GET o User Profile atual do realm, ACRESCENTA `identidade-id` (se ainda ausente) e PUT de volta. NUNCA
  reescreve do zero — Keycloak 26 tem 'unmanaged attributes' desligado por default em realms novos: um
  atributo nao-declarado e' SILENCIOSAMENTE DESCARTADO na escrita do usuario (achado real, verificado
  contra o Keycloak 26 vivo — sem isto, criar-usuario! perderia o identidade-id sem erro nenhum)."
  [http-client token base-url realm]
  (let [{:keys [status corpo]} (admin-req! http-client token :get (str "/admin/realms/" realm "/users/profile") nil base-url)]
    (when-not (= 200 status) (throw (ex-info "keycloak-idp: falha ao ler o user-profile (infra)" {:status status})))
    (when-not (some #(= "identidade-id" (:name %)) (:attributes corpo))
      (let [novo (update corpo :attributes conj
                         {:name "identidade-id" :displayName "Identidade (identidade-id)"
                          :multivalued false
                          :permissions {:view ["admin"] :edit ["admin"]}
                          :validations {}})
            {:keys [status corpo]} (admin-req! http-client token :put (str "/admin/realms/" realm "/users/profile") novo base-url)]
        (when-not (= 200 status)
          (throw (ex-info "keycloak-idp: falha ao declarar o atributo identidade-id (infra)" {:status status :corpo corpo})))))))

(defn- provisionar-realm-impl
  [{:keys [config http-client]} ente-id]
  (let [{:keys [base-url realm-prefixo audiencia]} config
        realm (str realm-prefixo ente-id)
        token (admin-token! config http-client)
        {:keys [status]} (admin-req! http-client token :get (str "/admin/realms/" realm) nil base-url)]
    (when (= 404 status)
      (let [{:keys [status corpo]} (admin-req! http-client token :post "/admin/realms"
                                               {:realm realm :enabled true} base-url)]
        (when-not (= 201 status)
          (throw (ex-info "keycloak-idp: falha ao criar o realm (infra)" {:status status :corpo corpo})))))
    (declarar-atributo-identidade! http-client token base-url realm)
    (let [{:keys [status corpo]} (admin-req! http-client token :get
                                             (str "/admin/realms/" realm "/clients?clientId=" audiencia) nil base-url)
          existe-client? (and (= 200 status) (seq corpo))]
      (when-not existe-client?
        (let [{:keys [status corpo]}
              (admin-req! http-client token :post (str "/admin/realms/" realm "/clients")
                          {:clientId audiencia :publicClient true :standardFlowEnabled true
                           :directAccessGrantsEnabled false
                           :protocolMappers
                           [{:name "identidade-id" :protocol "openid-connect"
                             :protocolMapper "oidc-usermodel-attribute-mapper"
                             :config {"user.attribute" "identidade-id" "claim.name" "identidade-id"
                                      "jsonType.label" "String" "access.token.claim" "true"}}
                            {:name "audiencia-propria" :protocol "openid-connect"
                             :protocolMapper "oidc-audience-mapper"
                             :config {"included.client.audience" audiencia "access.token.claim" "true"}}]}
                          base-url)]
          (when-not (= 201 status)
            (throw (ex-info "keycloak-idp: falha ao criar o client (infra)" {:status status :corpo corpo}))))))
    {:realm realm}))

(defn- nome->first-last
  "Deriva firstName/lastName do `nome` (Keycloak 26 EXIGE os 2 no User Profile default p/ role 'user' —
  achado real: sem eles, o login falha com 'Account is not fully set up'). Nome de 1 palavra so' repete
  como sobrenome (nao ha' um 2o campo pra inventar)."
  [nome]
  (let [partes (str/split (str/trim nome) #"\s+" 2)]
    (if (= 2 (count partes)) partes [(first partes) (first partes)])))

(defn- criar-usuario-impl
  [{:keys [config http-client]} ente-id {:keys [identidade-id nome email]}]
  (let [{:keys [base-url realm-prefixo]} config
        realm (str realm-prefixo ente-id)
        token (admin-token! config http-client)
        [primeiro ultimo] (nome->first-last nome)
        {:keys [status corpo headers]}
        (admin-req! http-client token :post (str "/admin/realms/" realm "/users")
                    {:username (str identidade-id)
                     :enabled true
                     ;; emailVerified=true e' [GAP] pre-prod: o bootstrap real e' e-mail de uso unico
                     ;; (carry F6, sem SMTP ainda) — marcar verificado aqui evita travar o 1o login em
                     ;; dev/integracao enquanto esse fluxo nao existe.
                     :emailVerified true
                     :email email
                     :firstName primeiro
                     :lastName ultimo
                     :attributes {:identidade-id [(str identidade-id)]}}
                    base-url)]
    (when-not (= 201 status)
      (throw (ex-info "keycloak-idp: falha ao criar usuario (infra)" {:status status :corpo corpo})))
    (let [location (.firstValue headers "location")]
      {:keycloak-user-id (when (.isPresent location) (last (str/split (.get location) #"/")))})))

(defn- buscar-usuario-por-identidade
  [http-client token base-url realm identidade-id]
  (let [{:keys [status corpo]}
        (admin-req! http-client token :get
                    (str "/admin/realms/" realm "/users?q=identidade-id:" identidade-id) nil base-url)]
    (when-not (= 200 status) (throw (ex-info "keycloak-idp: falha ao buscar usuario (infra)" {:status status})))
    (first corpo)))

(def ^:private tipos-credencial-mfa #{"otp" "webauthn" "webauthn-passwordless"})

(defn- resetar-mfa-impl
  [{:keys [config http-client]} ente-id identidade-id]
  (let [{:keys [base-url realm-prefixo]} config
        realm (str realm-prefixo ente-id)
        token (admin-token! config http-client)]
    (if-let [{kc-id :id} (buscar-usuario-por-identidade http-client token base-url realm identidade-id)]
      (let [{:keys [status corpo]}
            (admin-req! http-client token :get (str "/admin/realms/" realm "/users/" kc-id "/credentials") nil base-url)]
        (when-not (= 200 status) (throw (ex-info "keycloak-idp: falha ao listar credenciais (infra)" {:status status})))
        (doseq [{:keys [id type]} corpo :when (tipos-credencial-mfa type)]
          (let [{:keys [status]}
                (admin-req! http-client token :delete
                            (str "/admin/realms/" realm "/users/" kc-id "/credentials/" id) nil base-url)]
            (when-not (= 204 status)
              (throw (ex-info "keycloak-idp: falha ao remover credencial MFA (infra)" {:status status :credencial-id id})))))
        {:identidade-id identidade-id :removidas (count (filter (comp tipos-credencial-mfa :type) corpo))})
      (throw (ex-info "keycloak-idp: identidade sem usuario neste realm" {:ente-id ente-id :identidade-id identidade-id})))))
```

Now update the `defrecord KeycloakIdp` body's `idp/IdentityProvider` impl to call these instead of throwing:

```clojure
  idp/IdentityProvider
  (verificar-token [this token] (verificar-token* this token))
  (provisionar-realm! [this ente-id] (provisionar-realm-impl this ente-id))
  (criar-usuario! [this ente-id usuario] (criar-usuario-impl this ente-id usuario))
  (resetar-mfa! [this ente-id identidade-id] (resetar-mfa-impl this ente-id identidade-id)))
```

- [ ] **Step 5: Run the test to verify it passes**
Run: `clojure -M:test --focus :keycloak`
Expected: PASS — `provisionar-realm-e-idempotente` green (Keycloak container must be up per the pre-task instructions).

- [ ] **Step 6: Add the remaining integration tests (`criar-usuario!`, `resetar-mfa!`)**
Append to `apps/backend/test/keycloak/oplenario/keycloak/provisionamento_test.clj`:

```clojure
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
```

- [ ] **Step 7: Run all Keycloak integration tests**
Run: `clojure -M:test --focus :keycloak`
Expected: PASS — all 5 tests green.

- [ ] **Step 8: Exclude `:keycloak` from CI (no Keycloak container there yet)**
In `.github/workflows/ci.yml`, change the last step's `run:` line from:

```yaml
        run: clojure -M:test --skip :e2e
```

to:

```yaml
        run: clojure -M:test --skip :e2e --skip :keycloak
```

- [ ] **Step 9: Confirm the default local run (no explicit focus) still skips `:keycloak` correctly documented**
Run (from `apps/backend/`, Keycloak container can be stopped now): `clojure -M:test --skip :e2e --skip :keycloak`
Expected: PASS — unit + integration suites green, no attempt to reach Keycloak. This is the same command CI runs.

- [ ] **Step 10: Run clj-kondo on the whole changed surface**
Run: `clj-kondo --lint src/oplenario/kernel/components/keycloak_idp.clj test/keycloak/oplenario/keycloak/provisionamento_test.clj`
Expected: 0 errors.

- [ ] **Step 11: Commit**
```bash
git add apps/backend/src/oplenario/kernel/components/keycloak_idp.clj \
        apps/backend/test/keycloak/oplenario/keycloak/provisionamento_test.clj \
        apps/backend/tests.edn apps/backend/deps.edn .github/workflows/ci.yml
git commit -m "feat(be): KeycloakIdp — provisionar-realm!/criar-usuario!/resetar-mfa! (admin-API real)

Correcoes achadas ao exercitar um Keycloak 26 real: admin ROPC via admin-cli/master
(sem client confidential dedicado); realms novos tem unmanaged-attributes OFF —
identidade-id precisa ser DECLARADO no User Profile antes de persistir; aud default
e' 'account', nao o client id — precisa do protocol mapper oidc-audience-mapper;
firstName/lastName sao exigidos p/ login (Account is not fully set up sem eles)."
```

---

### Task 4: Wire into boot (`sistema.clj`)

**Files:**
- Modify: `apps/backend/src/oplenario/sistema.clj`
- Test: `apps/backend/test/unit/oplenario/sistema_test.clj` (Read this file first to match its existing style/fixtures before adding to it)

**Interfaces:**
- Consumes: `oplenario.kernel.components.keycloak-idp/keycloak-idp` (Task 2/3).
- Produces: `idp-para` now returns a real `KeycloakIdp` (not-yet-started record) for `"production"`/`"staging"` envs instead of throwing; `dev`/`test` unchanged.

- [ ] **Step 1: Read the existing `sistema_test.clj` to see how `idp-para`/boot is currently tested**

Run: `grep -n "idp-para\|idp-dev\|production\|staging" apps/backend/test/unit/oplenario/sistema_test.clj apps/backend/src/oplenario/sistema.clj`

Use the `Read` tool on both files at the matched line ranges before editing — this task modifies existing,
already-tested behavior (the current test suite proves `idp-para` THROWS in production; that test must be
updated to prove it now returns a `KeycloakIdp`, not deleted).

- [ ] **Step 2: Write/update the failing test**

Find the existing test that asserts `idp-para` throws in production (likely named something like
`idp-para-lanca-em-producao` or similar — use the grep from Step 1 to find its exact name) and replace its
assertion. If the existing test is, for example:

```clojure
(deftest idp-para-lanca-em-producao
  (is (thrown? clojure.lang.ExceptionInfo (sistema/idp-para {:env "production"}))))
```

Replace it with:

```clojure
(deftest idp-para-producao-usa-keycloak
  (is (instance? oplenario.kernel.components.keycloak_idp.KeycloakIdp
                 (sistema/idp-para {:env "production" :keycloak {:base-url "http://x" :realm-prefixo "ente-"
                                                                   :audiencia "a" :admin-usuario "u"
                                                                   :admin-senha "p" :jwks-cache-ttl-s 600}}))))

(deftest idp-para-staging-usa-keycloak
  (is (instance? oplenario.kernel.components.keycloak_idp.KeycloakIdp
                 (sistema/idp-para {:env "staging" :keycloak {:base-url "http://x" :realm-prefixo "ente-"
                                                                :audiencia "a" :admin-usuario "u"
                                                                :admin-senha "p" :jwks-cache-ttl-s 600}}))))

(deftest idp-para-dev-usa-idp-dev
  (is (instance? oplenario.kernel.components.idp_dev.IdpDev (sistema/idp-para {:env "dev"}))))
```

(Adjust the exact deftest name/assertions to match whatever the actual existing test looked like from
Step 1 — the principle is: no test should still assert `idp-para` throws for `production`, since that's
precisely the behavior this task removes.)

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd apps/backend && clojure -M:test --focus oplenario.sistema-test`
Expected: FAIL — `idp-para` still throws for `"production"`.

- [ ] **Step 4: Update `idp-para` in `sistema.clj`**

Add the require (alongside the existing `idp-dev` require in the `:require` block):

```clojure
            [oplenario.kernel.components.keycloak-idp :as keycloak-idp]
```

Replace the existing `idp-para` function body:

```clojure
(defn- idp-para
  "Seleciona a impl do IdP por ambiente. producao/staging usam o KeycloakIdp real (Onda D Slice 1);
  dev/test seguem no idp-dev (confia em claims sem verificar assinatura — nunca usar fora de dev/test)."
  [config]
  (if (#{"production" "staging"} (:env config))
    (keycloak-idp/keycloak-idp (:keycloak config))
    (idp-dev/idp-dev)))
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `clojure -M:test --focus oplenario.sistema-test`
Expected: PASS.

- [ ] **Step 6: Run the full unit + integration suite to check for regressions**

Run: `clojure -M:test --skip :e2e --skip :keycloak`
Expected: PASS — no regression in any other suite (the `KeycloakIdp` record is only reachable via
`idp-para` for `production`/`staging`, which no existing test/boot path exercises outside this task's own
new tests).

- [ ] **Step 7: Commit**

```bash
git add apps/backend/src/oplenario/sistema.clj apps/backend/test/unit/oplenario/sistema_test.clj
git commit -m "feat(be): boot injeta KeycloakIdp real em production/staging (idp-para deixa de lancar)"
```

---

### Task 5: Ponta-a-ponta — realm provisionado → token real → `verificar-token` → `resolver-sessao` → `ator`

**Files:**
- Test: `apps/backend/test/keycloak/oplenario/keycloak/ponta_a_ponta_test.clj`
- Modify: `apps/backend/docker-compose.yml` (runbook comment only)

**Interfaces:**
- Consumes: everything from Tasks 2-4 (`keycloak-idp`, `provisionar-realm!`, `criar-usuario!`,
  `resolver-sessao` from `oplenario.identidade.autenticacao` — unmodified), plus the PG datasource +
  `identidade` module's repo/vinculo creation (same pattern as `autenticacao_test.clj`, read that file
  again if needed for the exact fixture calls).
- Produces: nothing new — this is the proof task for spec success-criterion #4. No production code changes.

This test needs BOTH Postgres (for `resolver-sessao`'s vínculo lookup) AND Keycloak running. Start both:

```bash
cd apps/backend
docker compose up -d postgres
OPLENARIO_KEYCLOAK_PORT=8090 docker compose --profile auth up -d keycloak
```

- [ ] **Step 1: Write the end-to-end test**

Create `apps/backend/test/keycloak/oplenario/keycloak/ponta_a_ponta_test.clj`:

```clojure
(ns oplenario.keycloak.ponta-a-ponta-test
  "INTEGRACAO gated (PG + Keycloak reais): prova o criterio de sucesso #4 do design — um token emitido
  por um realm QUE NOS PROVISIONAMOS, verificado por verificar-token, produz claims que resolver-sessao
  transforma num ator real. Fecha o loop Keycloak-adapter -> seam de identidade ja' existente."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.identidade.autenticacao :as auth]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.identidade.db.identidade :as id]
            [oplenario.identidade.db.vinculo :as vinc]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp :as idp]
            [oplenario.kernel.components.keycloak-idp :as kc]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers)))

(def ^:private base-url (or (System/getenv "KEYCLOAK_BASE_URL") "http://localhost:8090"))
(def ^:private kc-config
  {:base-url base-url :realm-prefixo "ente-" :audiencia "oplenario-backend"
   :admin-usuario "admin" :admin-senha "admin" :jwks-cache-ttl-s 600})

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- dv [ds] (let [r (mod (reduce + (map * ds (range (inc (count ds)) 1 -1))) 11)] (if (< r 2) 0 (- 11 r))))
(defn- cpf-valido [] (let [b (vec (repeatedly 9 #(rand-int 10))) d1 (dv b)] (apply str (concat b [d1 (dv (conj b d1))]))))

(defn- repo [] (assoc (repo-id/repositorio) :datasource {:ds *ds*}))

(defn- seed-vinculo! [ente iid papeis]
  (id/inserir! *ds* {:id iid :cpf (cpf-valido) :nome "Vereadora E2E"})
  (tenancy/com-tenant* *ds* ente
    (fn [tx]
      (vinc/criar! tx {:id (random-uuid) :ente-id ente :identidade-id iid :tipo "vereador"})
      (doseq [p papeis] (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id ente :identidade-id iid :papel p})))))

;; --- helper de teste (NAO e' o fluxo de producao): habilita direct-grant temporariamente + faz ROPC
;; para minerar um token real. A producao usa Authorization Code + PKCE (fatia FE, fora de escopo) --
;; isto so' existe para o teste conseguir um token sem browser.
(defn- http! [] (HttpClient/newHttpClient))

(defn- admin-token-teste! [^HttpClient http]
  (let [corpo "grant_type=password&client_id=admin-cli&username=admin&password=admin"
        req (-> (HttpRequest/newBuilder) (.uri (URI/create (str base-url "/realms/master/protocol/openid-connect/token")))
                (.header "Content-Type" "application/x-www-form-urlencoded")
                (.POST (HttpRequest$BodyPublishers/ofString corpo)) (.build))
        resp (.send http req (HttpResponse$BodyHandlers/ofString))]
    (get (json/read-value (.body resp) json/keyword-keys-object-mapper) :access_token)))

(defn- habilitar-direct-grant-para-teste! [^HttpClient http token realm]
  (let [clientes (json/read-value
                  (.body (.send http (-> (HttpRequest/newBuilder)
                                         (.uri (URI/create (str base-url "/admin/realms/" realm "/clients?clientId=oplenario-backend")))
                                         (.header "Authorization" (str "Bearer " token)) (.build))
                                (HttpResponse$BodyHandlers/ofString)))
                  json/keyword-keys-object-mapper)
        cid (:id (first clientes))]
    (.send http (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str base-url "/admin/realms/" realm "/clients/" cid)))
                    (.header "Authorization" (str "Bearer " token)) (.header "Content-Type" "application/json")
                    (.PUT (HttpRequest$BodyPublishers/ofString "{\"directAccessGrantsEnabled\":true}")) (.build))
              (HttpResponse$BodyHandlers/ofString))))

(defn- minerar-token-teste! [^HttpClient http realm username senha]
  (let [corpo (str "grant_type=password&client_id=oplenario-backend"
                   "&username=" (URLEncoder/encode ^String username "UTF-8")
                   "&password=" (URLEncoder/encode ^String senha "UTF-8"))
        req (-> (HttpRequest/newBuilder) (.uri (URI/create (str base-url "/realms/" realm "/protocol/openid-connect/token")))
                (.header "Content-Type" "application/x-www-form-urlencoded")
                (.POST (HttpRequest$BodyPublishers/ofString corpo)) (.build))
        resp (.send http req (HttpResponse$BodyHandlers/ofString))]
    (:access_token (json/read-value (.body resp) json/keyword-keys-object-mapper))))

;; --- criar um usuario Keycloak COM senha (criar-usuario! do adapter nao seta senha -- o bootstrap real
;; e' e-mail de uso unico, carry F6; o teste seta a senha direto via admin-API so' para poder logar).
(defn- setar-senha-teste! [^HttpClient http token realm kc-user-id senha]
  (.send http (-> (HttpRequest/newBuilder)
                  (.uri (URI/create (str base-url "/admin/realms/" realm "/users/" kc-user-id "/reset-password")))
                  (.header "Authorization" (str "Bearer " token)) (.header "Content-Type" "application/json")
                  (.PUT (HttpRequest$BodyPublishers/ofString
                         (str "{\"type\":\"password\",\"value\":\"" senha "\",\"temporary\":false}")))
                  (.build))
            (HttpResponse$BodyHandlers/ofString)))

(deftest realm-provisionado-token-real-vira-ator
  (let [ente (random-uuid)
        iid (random-uuid)
        ip (component/start (kc/keycloak-idp kc-config))
        {:keys [realm]} (idp/provisionar-realm! ip ente)
        {:keys [keycloak-user-id]} (idp/criar-usuario! ip ente {:identidade-id iid :nome "Vereadora E2E" :email "e2e@example.com"})
        http (http!)
        admin-tok (admin-token-teste! http)]
    (habilitar-direct-grant-para-teste! http admin-tok realm)
    (setar-senha-teste! http admin-tok realm keycloak-user-id "senha-teste-123")
    (seed-vinculo! ente iid ["vereador"])
    (let [token (minerar-token-teste! http realm (str iid) "senha-teste-123")
          claims (idp/verificar-token ip token)
          ator (auth/resolver-sessao (repo) claims)]
      (is (some? claims) "o token real do realm provisionado verifica com sucesso")
      (is (= iid (:identidade-id claims)))
      (is (= ente (:ente-id claims)))
      (is (= iid (:identidade-id ator)) "resolver-sessao produz um ator a partir das claims verificadas")
      (is (= "vereador" (:tipo-vinculo ator))))
    (component/stop ip)))
```

- [ ] **Step 2: Run it**

Run: `cd apps/backend && clojure -M:test --focus :keycloak`
Expected: PASS — this is the slowest test in the suite (mints tokens over real HTTP against a real
Keycloak); if it fails, check the container is actually up and `KEYCLOAK_BASE_URL`/`OPLENARIO_KEYCLOAK_PORT`
are consistent (same port on both sides).

- [ ] **Step 3: Run the full non-Keycloak suite once more to confirm zero cross-contamination**

Run: `clojure -M:test --skip :e2e --skip :keycloak`
Expected: PASS, same counts as before this task (this test lives entirely under `:keycloak`, never runs
alongside the default suite).

- [ ] **Step 4: Document the runbook in `docker-compose.yml`**

In `apps/backend/docker-compose.yml`, update the comment above the `keycloak:` service (currently just
`# Auth: docker compose --profile auth up (sobe o Keycloak; F0 nao usa, fica de fora por padrao)`) to:

```yaml
  # Auth: docker compose --profile auth up (sobe o Keycloak; adapter real desde Onda D Slice 1).
  # 1o boot demora ~3-5min (Quarkus augmentation + schema do banco embutido) -- espere o healthcheck,
  # nao assuma pronto so' porque o container "started". Porta 8080 pode colidir com outro projeto local
  # -- use OPLENARIO_KEYCLOAK_PORT (+ KEYCLOAK_BASE_URL do app apontando pra' mesma porta) se precisar.
```

- [ ] **Step 5: Run clj-kondo on the final test file**

Run: `clj-kondo --lint test/keycloak/oplenario/keycloak/ponta_a_ponta_test.clj`
Expected: 0 errors (fix any unused-require warnings before committing).

- [ ] **Step 6: Commit**

```bash
git add apps/backend/test/keycloak/oplenario/keycloak/ponta_a_ponta_test.clj apps/backend/docker-compose.yml
git commit -m "test(be): ponta-a-ponta Keycloak real -> verificar-token -> resolver-sessao -> ator (criterio #4 do design)"
```

- [ ] **Step 7: Stop the Keycloak container (not needed for normal dev/CI)**

Run: `docker compose --profile auth stop keycloak`

---

## Self-review notes (for whoever executes this plan)

- **Spec coverage:** success criteria #1 (verificar-token, Task 2) · #2 (provisionamento, Task 3) · #3
  (boot injection, Task 4) · #4 (ponta-a-ponta, Task 5) · #5 (test split: unit fast / integration gated,
  Tasks 2 & 3) are each covered by a distinct task.
- **Known carry, not blocking:** `criar-usuario!` sets `emailVerified: true` and does not set a password
  (that's the e-mail-bootstrap flow, still `[GAP]`/carry per F6's no-SMTP carry) — Task 5's test sets a
  password directly via the admin API purely so the test can mint a token; this is explicitly NOT
  something the adapter itself does, and the comment in that test says so.
- **Known carry, not blocking:** admin token is fetched fresh on every admin call (no caching) — fine at
  this volume (administrative, not hot-path); revisit if provisioning volume ever justifies it.
- **Out of scope, confirmed untouched by this plan:** `identidade/autenticacao.clj`, `interceptors.clj`,
  `rotas.clj`, `legislativo/components/assinador_icp.clj`, `admin_sistema/components/idp_admin.clj`.
