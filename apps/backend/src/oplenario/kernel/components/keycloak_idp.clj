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
            [jsonista.core :as json]
            [oplenario.kernel.components.idp :as idp])
  (:import (com.auth0.jwk JwkProviderBuilder JwkException SigningKeyNotFoundException NetworkException RateLimitReachedException)
           (com.auth0.jwt JWT)
           (com.auth0.jwt.algorithms Algorithm)
           (com.auth0.jwt.exceptions JWTVerificationException JWTDecodeException)
           (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers)
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

(defn- garantir-client!
  "GET-then-create idempotente de um client no realm: consulta por `client-id`; se ja existe, no-op; senao
  POST do `payload`. Compartilhado pelos clients de audiencia (API) e web (PKCE publico) — a unica coisa
  que difere entre eles e' o payload, entao a mecanica idempotente vive aqui uma vez so."
  [http-client token base-url realm client-id payload]
  (let [{:keys [status corpo]} (admin-req! http-client token :get
                                           (str "/admin/realms/" realm "/clients?clientId=" client-id) nil base-url)
        existe-client? (and (= 200 status) (seq corpo))]
    (when-not existe-client?
      (let [{:keys [status corpo]} (admin-req! http-client token :post
                                               (str "/admin/realms/" realm "/clients") payload base-url)]
        (when-not (= 201 status)
          (throw (ex-info "keycloak-idp: falha ao criar o client (infra)"
                          {:status status :corpo corpo :client-id client-id})))))))

(defn- habilitar-passkey!
  "Garante que a required action de passkey fique habilitada no realm; sem isto, marcar o usuario com ela
  e' silenciosamente ignorado (mesma armadilha do User Profile, ver declarar-atributo-identidade!). O
  default de fabrica desta required action VARIA por versao/modo de import do Keycloak — achado real:
  contra o Keycloak 26.0.0 (`start-dev`) ela ja nasce `enabled:true` num realm recem-criado, contrariando
  a premissa original deste design. Por isso afirmamos o estado desejado idempotentemente (PUT do mesmo
  estado nao falha) em vez de depender do default de qualquer versao especifica. Erro de infra LANCA —
  nunca segue em frente com o realm parcialmente configurado."
  [http-client token base-url realm]
  (let [{:keys [status corpo]}
        (admin-req! http-client token :put
                    (str "/admin/realms/" realm "/authentication/required-actions/webauthn-register-passwordless")
                    {:alias "webauthn-register-passwordless" :name "Webauthn Register Passwordless"
                     :providerId "webauthn-register-passwordless" :enabled true :defaultAction false
                     :priority 30 :config {}}
                    base-url)]
    (when-not (= 204 status)
      (throw (ex-info "keycloak-idp: falha ao habilitar a required action de passkey (infra)"
                      {:status status :corpo corpo})))))

(defn- configurar-smtp!
  "Aponta o realm p/ o relay. Quem envia o convite e' o Keycloak — p/ nos e' config, nao codigo (nao
  confundir com o carry F6, que e' o e-mail TRANSACIONAL da app). Prod = relay BR (§22.9 Eixo 12).
  PUT PARCIAL (so' :realm + :smtpServer no corpo) em vez do padrao GET-then-merge de
  declarar-atributo-identidade! — testado empiricamente contra o Keycloak 26 vivo (suite completa,
  incluindo criacao de client/usuario no MESMO realm logo em seguida): ao contrario do User Profile (que
  descarta atributo nao-declarado por causa de 'unmanaged attributes' desligado), um PUT parcial na raiz
  do realm NAO zera os demais campos omitidos. Se uma versao futura do KC mudar esse comportamento,
  convergir p/ GET-then-merge. Erro de infra LANCA — nunca segue em frente com o realm parcialmente
  configurado."
  [http-client token base-url realm {:keys [host port from ssl starttls auth usuario senha]}]
  (let [{:keys [status corpo]}
        (admin-req! http-client token :put (str "/admin/realms/" realm)
                    {:realm realm
                     :smtpServer (cond-> {:host host :port (str port) :from from
                                          :ssl (str (boolean ssl)) :starttls (str (boolean starttls))
                                          :auth (str (boolean auth))}
                                   auth (assoc :user usuario :password senha))}
                    base-url)]
    (when-not (= 204 status)
      (throw (ex-info "keycloak-idp: falha ao configurar o SMTP do realm (infra)"
                      {:status status :corpo corpo})))))

(defn- provisionar-realm-impl
  [{:keys [config http-client]} ente-id]
  (let [{:keys [base-url realm-prefixo audiencia web-client-id redirect-uris web-origins smtp]} config
        realm (str realm-prefixo ente-id)
        token (admin-token! config http-client)
        {:keys [status]} (admin-req! http-client token :get (str "/admin/realms/" realm) nil base-url)]
    (when (= 404 status)
      (let [{:keys [status corpo]} (admin-req! http-client token :post "/admin/realms"
                                               {:realm realm :enabled true} base-url)]
        (when-not (= 201 status)
          (throw (ex-info "keycloak-idp: falha ao criar o realm (infra)" {:status status :corpo corpo})))))
    (declarar-atributo-identidade! http-client token base-url realm)
    (habilitar-passkey! http-client token base-url realm)
    (configurar-smtp! http-client token base-url realm smtp)
    ;; Client de audiencia (API): valida o access-token; carrega o mapper de identidade-id + a audiencia propria.
    (garantir-client! http-client token base-url realm audiencia
                      {:clientId audiencia :publicClient true :standardFlowEnabled true
                       :directAccessGrantsEnabled false
                       :protocolMappers
                       [{:name "identidade-id" :protocol "openid-connect"
                         :protocolMapper "oidc-usermodel-attribute-mapper"
                         :config {"user.attribute" "identidade-id" "claim.name" "identidade-id"
                                  "jsonType.label" "String" "access.token.claim" "true"}}
                        {:name "audiencia-propria" :protocol "openid-connect"
                         :protocolMapper "oidc-audience-mapper"
                         :config {"included.client.audience" audiencia "access.token.claim" "true"}}]})
    ;; Client web (PKCE publico): o navegador troca o code no BFF; sem client-secret, sem grant direto de senha.
    (garantir-client! http-client token base-url realm web-client-id
                      {:clientId web-client-id :publicClient true :standardFlowEnabled true
                       :directAccessGrantsEnabled false
                       :redirectUris redirect-uris :webOrigins web-origins
                       :attributes {"pkce.code.challenge.method" "S256"}
                       ;; MESMOS mappers do client de audiencia: o token PKCE tem de carregar `identidade-id`
                       ;; E a audiencia `oplenario-backend`, senao verificar-token (.withAudience + claim
                       ;; identidade-id) rejeita -> "token invalido" no mint. O login real usa ESTE client,
                       ;; entao sem os mappers o loop inteiro falha no mint (achado T17).
                       :protocolMappers
                       [{:name "identidade-id" :protocol "openid-connect"
                         :protocolMapper "oidc-usermodel-attribute-mapper"
                         :config {"user.attribute" "identidade-id" "claim.name" "identidade-id"
                                  "jsonType.label" "String" "access.token.claim" "true"}}
                        {:name "audiencia-backend" :protocol "openid-connect"
                         :protocolMapper "oidc-audience-mapper"
                         :config {"included.client.audience" audiencia "access.token.claim" "true"}}]})
    {:realm realm}))

(defn- nome->first-last
  "Deriva firstName/lastName do `nome` (Keycloak 26 EXIGE os 2 no User Profile default p/ role 'user' —
  achado real: sem eles, o login falha com 'Account is not fully set up'). Nome de 1 palavra so' repete
  como sobrenome (nao ha' um 2o campo pra inventar)."
  [nome]
  (let [partes (str/split (str/trim nome) #"\s+" 2)]
    (if (= 2 (count partes)) partes [(first partes) (first partes)])))

(defn- buscar-usuario-por-identidade
  [http-client token base-url realm identidade-id]
  (let [{:keys [status corpo]}
        (admin-req! http-client token :get
                    (str "/admin/realms/" realm "/users?q=identidade-id:" identidade-id) nil base-url)]
    (when-not (= 200 status) (throw (ex-info "keycloak-idp: falha ao buscar usuario (infra)" {:status status})))
    (first corpo)))

(defn- criar-usuario-impl
  "GET-then-create (idempotente, mesma forma de garantir-client!/provisionar-realm-impl): re-provisionar a
  MESMA identidade-id no MESMO ente devolve o usuario ja existente em vez de lancar em 409 'User exists
  with same email'. Nasce com a required action de passkey: o KC OBRIGA o cadastro antes de qualquer acao
  (§22.5.2 eixo F). emailVerified NAO e' mais forcado a true — o [GAP] existia so' porque nao havia SMTP
  configurado no realm (Task 2 fechou isso); agora o KC verifica de verdade via o fluxo de e-mail."
  [{:keys [config http-client]} ente-id {:keys [identidade-id nome email]}]
  (let [{:keys [base-url realm-prefixo]} config
        realm (str realm-prefixo ente-id)
        token (admin-token! config http-client)]
    (if-let [existente (buscar-usuario-por-identidade http-client token base-url realm identidade-id)]
      {:keycloak-user-id (:id existente)}
      (let [[primeiro ultimo] (nome->first-last nome)
            {:keys [status corpo headers]}
            (admin-req! http-client token :post (str "/admin/realms/" realm "/users")
                        {:username (str identidade-id)
                         :enabled true
                         :email email
                         :firstName primeiro
                         :lastName ultimo
                         :requiredActions ["webauthn-register-passwordless"]
                         :attributes {:identidade-id [(str identidade-id)]}}
                        base-url)]
        (when-not (= 201 status)
          (throw (ex-info "keycloak-idp: falha ao criar usuario (infra)" {:status status :corpo corpo})))
        (let [location (.firstValue headers "location")]
          {:keycloak-user-id (when (.isPresent location) (last (str/split (.get location) #"/")))})))))

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

;; ---------------------------------------------------------------------------------------------
;; Component
;; ---------------------------------------------------------------------------------------------

(defn- convidar-impl
  "Stub temporario para compilar — impl real (envio do codigo de uso unico ao e-mail institucional) e' a
  Task 4."
  [_ _ente-id _identidade-id]
  (throw (ex-info "convidar!: nao implementado (Task 4)" {:tipo :idp/nao-implementado})))

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
  (provisionar-realm! [this ente-id] (provisionar-realm-impl this ente-id))
  (criar-usuario! [this ente-id usuario] (criar-usuario-impl this ente-id usuario))
  (convidar! [this ente-id identidade-id] (convidar-impl this ente-id identidade-id))
  (resetar-mfa! [this ente-id identidade-id] (resetar-mfa-impl this ente-id identidade-id)))

(defn keycloak-idp
  "Cria o Component KeycloakIdp (NAO-iniciado — chame component/start). `config` = o mapa `:keycloak` do
  config.edn. `jwks-provider-fn` e' opcional — (fn [config iss] -> JwkProvider); default =
  `jwks-provider-http` (real, HTTP). Testes injetam uma fake sem rede (inversao de dependencia, §5 do
  design)."
  ([config] (keycloak-idp config jwks-provider-http))
  ([config jwks-provider-fn]
   (map->KeycloakIdp {:config config :jwks-provider-fn jwks-provider-fn})))
