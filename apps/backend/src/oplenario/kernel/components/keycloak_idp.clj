(ns oplenario.kernel.components.keycloak-idp
  "Impl REAL do IdentityProvider (§22.5) — Keycloak, realm-por-tenant (Onda D Slice 1, F1.4-carry
  parcialmente fechado: o adapter de tenant + o broker gov.br do cidadao, ADR-0015; operador segue carry). Verificacao de
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

(defn- prefixos-issuer
  "Prefixos de issuer ACEITOS: a URL interna (:base-url, falada entre containers) E a publica
  (:base-url-publico, browser-facing), ambas concatenadas com /realms/<prefixo>. Sao CONFIG DE DEPLOY,
  as duas confiaveis. Aceitar as duas conserta o split-horizon: num deploy onde o browser alcanca o
  Keycloak pela URL publica (reverse proxy), o token carrega o issuer PUBLICO, mas o backend so' conhecia
  a interna — validar so' contra :base-url rejeitava TODO login real ('backend rejeitou o token', com o
  redirect ja' corrigido). base-url-publico ausente/vazio simplesmente nao entra na lista."
  [{:keys [base-url base-url-publico realm-prefixo]}]
  (->> [base-url base-url-publico]
       (remove str/blank?)
       distinct
       (mapv #(str % "/realms/" realm-prefixo))))

(defn issuer-valido?
  [config iss]
  (boolean
   (when iss
     (some (fn [prefixo]
             (and (str/starts-with? iss prefixo)
                  (uuid-valido? (subs iss (count prefixo)))))
           (prefixos-issuer config)))))

(defn ente-id-do-issuer
  "Deriva o ente-id do issuer JA' VALIDADO por `issuer-valido?` — nunca de um claim auto-declarado.
  Usa o MESMO conjunto de prefixos (interno + publico): remove aquele que casa e le' o UUID restante."
  [config iss]
  (some (fn [prefixo]
          (when (str/starts-with? iss prefixo)
            (let [resto (subs iss (count prefixo))]
              (when (uuid-valido? resto) (UUID/fromString resto)))))
        (prefixos-issuer config)))

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

(defn claim-str
  "Claim textual do token JA' verificado; ausente/nulo -> nil."
  [verificado nome]
  (let [c (.getClaim verificado nome)]
    (when-not (or (nil? c) (.isNull c) (.isMissing c)) (.asString c))))

(defn verificar-jwt
  "O nucleo de verificacao, comum ao realm da Casa (`verificar-token*`) e ao realm do operador (ADR-0016): o
  `iss` passa pela allowlist `emissor-aceito?` ANTES de qualquer chave ser buscada; depois JWKS por `kid`, RS256,
  issuer exato, `audiencia` e `exp`. `extrair` recebe o token verificado + o iss e monta as claims. MESMO
  contrato fail-closed (e a mesma ORDEM de catches) documentado em `verificar-token*`: problema do token -> nil;
  infra da JWKS -> propaga."
  [{:keys [config jwks-cache jwks-provider-fn]} emissor-aceito? audiencia token extrair]
  (try
    (let [nao-verificado (JWT/decode token)
          iss (.getIssuer nao-verificado)]
      (when (and iss (emissor-aceito? iss))
        (let [provider   (provider-para! jwks-cache jwks-provider-fn config iss)
              kid        (.getKeyId nao-verificado)
              jwk        (.get provider kid)
              chave-pub  ^RSAPublicKey (.getPublicKey jwk)
              algoritmo  (Algorithm/RSA256 chave-pub nil)
              verificado (-> (JWT/require algoritmo)
                             (.withIssuer (into-array String [iss]))
                             (.withAudience (into-array String [audiencia]))
                             (.build)
                             (.verify token))]
          (extrair verificado iss))))
    (catch NetworkException e (throw e))
    (catch RateLimitReachedException e (throw e))
    (catch SigningKeyNotFoundException _ nil)
    (catch JwkException _ nil)
    (catch JWTVerificationException _ nil)
    (catch JWTDecodeException _ nil)
    (catch IllegalArgumentException _ nil)
    (catch ClassCastException _ nil)))

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
  [{:keys [config] :as comp} token]
  (verificar-jwt comp #(issuer-valido? config %) (:audiencia config) token
    (fn [verificado iss]
      (let [identidade-id-str (claim-str verificado "identidade-id")
            idp-sessao (claim-str verificado "idp")]
        (cond-> {:sub           (.getSubject verificado)
                 :identidade-id (when identidade-id-str (UUID/fromString identidade-id-str))
                 :ente-id       (ente-id-do-issuer config iss)
                 :exp           (some-> verificado .getExpiresAtAsInstant .getEpochSecond int)}
          ;; ADR-0015: por qual IdP a pessoa entrou (nota de sessao do realm) + o CPF e o nome que o gov.br
          ;; verificou. So' presentes num login brokered — o login institucional segue com as 4 chaves.
          idp-sessao (assoc :idp idp-sessao
                            :govbr-sub (claim-str verificado "govbr-sub")
                            :nome (claim-str verificado "govbr-nome")))))))

;; ---------------------------------------------------------------------------------------------
;; Admin API (provisionamento) — java.net.http + jsonista, sem lib HTTP nova.
;; ---------------------------------------------------------------------------------------------

(defn- body->json [m] (json/write-value-as-string m))
(defn- json->body [s] (when (seq s) (json/read-value s json/keyword-keys-object-mapper)))

(defn admin-token!
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

(defn admin-req!
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
  "GET o User Profile atual do realm, ACRESCENTA `identidade-id` (e, com gov.br, `govbr-sub`) se ainda ausentes e
  PUT de volta. NUNCA reescreve do zero — Keycloak 26 tem 'unmanaged attributes' desligado por default em realms
  novos: um atributo nao-declarado e' SILENCIOSAMENTE DESCARTADO na escrita do usuario (achado real, verificado
  contra o Keycloak 26 vivo — sem isto, criar-usuario! perderia o identidade-id sem erro nenhum). Os dois so' o
  admin ve'/edita: a pessoa nunca troca o proprio CPF nem a propria identidade.

  Com gov.br (ADR-0015), `email` e `lastName` deixam de ser exigidos DA PESSOA (so' no console do admin): do gov.br
  entram so' nome e CPF, e sem isto o Keycloak pararia o cidadao numa tela de 'complete seu perfil'. O usuario
  institucional continua nascendo com os dois, preenchidos por `criar-usuario!`."
  ([http-client token base-url realm] (declarar-atributo-identidade! http-client token base-url realm false))
  ([http-client token base-url realm govbr?]
   (let [{:keys [status corpo]} (admin-req! http-client token :get (str "/admin/realms/" realm "/users/profile") nil base-url)
         _ (when-not (= 200 status) (throw (ex-info "keycloak-idp: falha ao ler o user-profile (infra)" {:status status})))
         desejados (cond-> [{:name "identidade-id" :displayName "Identidade (identidade-id)"}]
                     govbr? (conj {:name "govbr-sub" :displayName "CPF verificado pelo gov.br (govbr-sub)"}
                                  {:name "govbr-nome" :displayName "Nome verificado pelo gov.br (govbr-nome)"}))
         faltando (remove (fn [d] (some #(= (:name d) (:name %)) (:attributes corpo))) desejados)
         so-admin {:roles ["admin"]}
         relaxar (fn [attrs] (mapv #(cond-> % (and (#{"email" "lastName"} (:name %)) (some? (:required %)))
                                      (assoc :required so-admin))
                                   attrs))
         novo (cond-> (update corpo :attributes into
                              (map #(assoc % :multivalued false :permissions {:view ["admin"] :edit ["admin"]}
                                           :validations {})
                                   faltando))
                govbr? (update :attributes relaxar))]
     (when (not= novo corpo)
       (let [{:keys [status corpo]} (admin-req! http-client token :put (str "/admin/realms/" realm "/users/profile") novo base-url)]
         (when-not (= 200 status)
           (throw (ex-info "keycloak-idp: falha ao declarar os atributos do realm (infra)" {:status status :corpo corpo}))))))))

(declare garantir-mappers-do-client!)

(defn garantir-client!
  "GET-then-converge idempotente de um client no realm: consulta por `client-id`; se NAO existe, POST do
  `payload`; se JA existe, PUT convergindo os campos declarativos que mudam entre deploys
  (redirectUris/webOrigins) sobre a representacao atual — os mappers sao subrecursos e ficam intactos.
  Compartilhado pelos clients de audiencia (API) e web (PKCE publico); a unica coisa que difere entre
  eles e' o payload, entao a mecanica vive aqui uma vez so.

  Por que CONVERGE e nao 'cria-ou-nada' (achado do teste da homolog, metodo docs/20): a versao anterior
  fazia no-op quando o client existia, entao um `oplenario-web` criado uma vez com redirect de localhost
  NUNCA era corrigido por re-provisionar — e todo login em prod quebrava com 'Invalid redirect_uri'.
  Idempotente de verdade e' convergir pro estado desejado, nao parar no primeiro que existe."
  [http-client token base-url realm client-id payload]
  (let [{:keys [status corpo]} (admin-req! http-client token :get
                                           (str "/admin/realms/" realm "/clients?clientId=" client-id) nil base-url)
        existente (and (= 200 status) (first corpo))]
    (if existente
      ;; PUT da representacao ATUAL com os campos de lista sobrepostos (mesmo padrao GET-then-PUT de
      ;; declarar-atributo-identidade!). Nao reescreve id/mappers/attributes — so' converge o que muda.
      (let [alvo (merge existente (select-keys payload [:redirectUris :webOrigins]))
            {:keys [status corpo]} (admin-req! http-client token :put
                                               (str "/admin/realms/" realm "/clients/" (:id existente)) alvo base-url)]
        (when-not (= 204 status)
          (throw (ex-info "keycloak-idp: falha ao atualizar o client (infra)"
                          {:status status :corpo corpo :client-id client-id})))
        ;; mapper novo (ex.: os do gov.br, ADR-0015) tambem chega a realm ja' provisionado — cria so' os que faltam
        (garantir-mappers-do-client! http-client token base-url realm (:id existente) (:protocolMappers payload)))
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

(defn configurar-smtp!
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

;; ---------------------------------------------------------------------------------------------
;; gov.br (ADR-0015): o broker do cidadao no realm da Casa
;; ---------------------------------------------------------------------------------------------

(def ^:private govbr-alias "govbr")
(def ^:private govbr-fluxo-primeiro-login "govbr-primeiro-login")
(def ^:private govbr-realm-simulado "govbr-simulado")

(defn govbr-endpoints
  "Config `:govbr` ({:ambiente :client-id :client-secret ...}, com :base-url/:base-url-publico do Keycloak p/ o
  simulado) -> as URLs e a claim do CPF que o IdP do realm usa. nil = sem gov.br (o realm nao ganha o broker e a
  descoberta diz que nao ha' botao). O `sub` do Login Unico E' o CPF; o simulado (realm no proprio Keycloak, dev/
  demo/CI) emite o CPF na claim `cpf` porque o sub de usuario do Keycloak e' um UUID. No simulado o NAVEGADOR vai
  pela URL publica e o broker (servidor-a-servidor) pela interna — mesmo split-horizon do resto do realm."
  [{:keys [ambiente base-url base-url-publico] :as g}]
  (when g
    (let [login-unico (fn [raiz] {:emissor (str raiz "/") :url-autorizacao (str raiz "/authorize")
                                  :url-token (str raiz "/token") :url-jwks (str raiz "/jwk")
                                  :url-userinfo (str raiz "/userinfo") :claim-cpf "sub"})
          base (case ambiente
                 "producao"    (login-unico "https://sso.acesso.gov.br")
                 "homologacao" (login-unico "https://sso.staging.acesso.gov.br")
                 "simulado"    (let [interno (str base-url "/realms/" govbr-realm-simulado "/protocol/openid-connect")
                                     publico (str (if (str/blank? base-url-publico) base-url base-url-publico)
                                                  "/realms/" govbr-realm-simulado "/protocol/openid-connect")]
                                 {:emissor (str base-url "/realms/" govbr-realm-simulado)
                                  :url-autorizacao (str publico "/auth") :url-token (str interno "/token")
                                  :url-jwks (str interno "/certs") :url-userinfo (str interno "/userinfo")
                                  :claim-cpf "cpf"})
                 (throw (ex-info "keycloak-idp: :govbr :ambiente desconhecido (producao|homologacao|simulado)"
                                 {:ambiente ambiente})))]
      (merge base (select-keys g [:client-id :client-secret :escopos])))))

(def ^:private mappers-de-sessao
  "Mappers comuns aos dois clients: o CPF e o nome que o gov.br verificou (`govbr-sub`, `govbr-nome` — o nome vem num
  atributo proprio porque a claim `name` do Keycloak e' firstName+lastName, que o importador padrao remonta a partir
  de given/family_name quando o provedor os manda) e POR QUAL IdP a pessoa entrou nesta
  sessao (`idp`, nota `identity_provider` que o Keycloak grava no login brokered). O backend decide a sessao de
  cidadao pelo `idp`, nunca pelo cliente."
  [{:name "govbr-sub" :protocol "openid-connect" :protocolMapper "oidc-usermodel-attribute-mapper"
    :config {"user.attribute" "govbr-sub" "claim.name" "govbr-sub" "jsonType.label" "String"
             "access.token.claim" "true"}}
   {:name "govbr-nome" :protocol "openid-connect" :protocolMapper "oidc-usermodel-attribute-mapper"
    :config {"user.attribute" "govbr-nome" "claim.name" "govbr-nome" "jsonType.label" "String"
             "access.token.claim" "true"}}
   {:name "idp" :protocol "openid-connect" :protocolMapper "oidc-usersessionmodel-note-mapper"
    :config {"user.session.note" "identity_provider" "claim.name" "idp" "jsonType.label" "String"
             "access.token.claim" "true"}}])

(defn lista [corpo] (when (sequential? corpo) corpo))

(defn exigir! [status esperados msg info]
  (when-not (contains? esperados status)
    (throw (ex-info (str "keycloak-idp: " msg " (infra)") (assoc info :status status)))))

(defn- garantir-mappers-do-client!
  "Converge os protocol mappers de um client JA' existente: cria os que faltam (por nome). Os que existem ficam."
  [http-client token base-url realm client-uuid mappers]
  (let [caminho (str "/admin/realms/" realm "/clients/" client-uuid "/protocol-mappers/models")
        {:keys [status corpo]} (admin-req! http-client token :get caminho nil base-url)
        _ (exigir! status #{200} "falha ao ler os mappers do client" {})
        existentes (set (map :name (lista corpo)))]
    (doseq [m mappers :when (not (existentes (:name m)))]
      (let [{:keys [status corpo]} (admin-req! http-client token :post caminho m base-url)]
        (exigir! status #{201} "falha ao criar mapper do client" {:mapper (:name m) :corpo corpo})))))

(defn- garantir-fluxo-primeiro-login!
  "O 1o login pelo gov.br so' CRIA o usuario (`idp-create-user-if-unique`, REQUIRED). Sem 'revisar perfil' e sem
  'vincular a conta existente': um gov.br nunca assume a conta de passkey de ninguem (ADR-0015)."
  [http-client token base-url realm]
  (let [raiz (str "/admin/realms/" realm "/authentication/flows")
        {:keys [status corpo]} (admin-req! http-client token :get raiz nil base-url)]
    (exigir! status #{200} "falha ao listar fluxos" {})
    (when-not (some #(= govbr-fluxo-primeiro-login (:alias %)) (lista corpo))
      (let [{:keys [status corpo]} (admin-req! http-client token :post raiz
                                               {:alias govbr-fluxo-primeiro-login :providerId "basic-flow"
                                                :description "1o login pelo gov.br: so' cria o usuario (ADR-0015)"
                                                :topLevel true :builtIn false}
                                               base-url)]
        (exigir! status #{201} "falha ao criar o fluxo de 1o login do gov.br" {:corpo corpo})))
    (let [execs (str raiz "/" govbr-fluxo-primeiro-login "/executions")
          criar-se-unico #(some (fn [e] (when (= "idp-create-user-if-unique" (:providerId e)) e)) %)
          ler #(let [{:keys [status corpo]} (admin-req! http-client token :get execs nil base-url)]
                 (exigir! status #{200} "falha ao ler as execucoes do fluxo" {})
                 (lista corpo))]
      (when-not (criar-se-unico (ler))
        (let [{:keys [status corpo]} (admin-req! http-client token :post (str execs "/execution")
                                                 {:provider "idp-create-user-if-unique"} base-url)]
          (exigir! status #{201} "falha ao incluir 'criar se unico' no fluxo" {:corpo corpo})))
      (when-let [e (criar-se-unico (ler))]
        (when-not (= "REQUIRED" (:requirement e))
          (let [{:keys [status corpo]} (admin-req! http-client token :put execs (assoc e :requirement "REQUIRED")
                                                   base-url)]
            (exigir! status #{202 204} "falha ao exigir 'criar se unico'" {:corpo corpo})))))))

(defn- garantir-idp-govbr!
  "O IdP gov.br do realm (GET-then-converge) + os mappers do broker. Do gov.br entram SO' o CPF e o nome, como a
  tela 'Entrar para participar' promete — nada de e-mail (sem e-mail tambem nao ha' colisao com a conta
  institucional de quem e' servidor e cidadao). Escondido da tela de login institucional: o portal chega aqui com
  `kc_idp_hint=govbr`."
  [http-client token base-url realm {:keys [emissor url-autorizacao url-token url-jwks url-userinfo claim-cpf
                                            client-id client-secret escopos]}]
  (let [raiz (str "/admin/realms/" realm "/identity-provider/instances")
        payload {:alias govbr-alias :displayName "gov.br" :providerId "oidc" :enabled true
                 :trustEmail false :storeToken false :linkOnly false :addReadTokenRoleOnCreate false
                 :firstBrokerLoginFlowAlias govbr-fluxo-primeiro-login
                 :config {"issuer" emissor "authorizationUrl" url-autorizacao "tokenUrl" url-token
                          "jwksUrl" url-jwks "userInfoUrl" url-userinfo "useJwksUrl" "true" "validateSignature" "true"
                          "clientId" client-id "clientSecret" client-secret "clientAuthMethod" "client_secret_basic"
                          "pkceEnabled" "true" "pkceMethod" "S256"
                          "defaultScope" (or escopos "openid email profile")
                          "syncMode" "FORCE" "hideOnLoginPage" "true" "guiOrder" "1"}}
        {:keys [status corpo]} (admin-req! http-client token :get (str raiz "/" govbr-alias) nil base-url)]
    (case status
      404 (let [{:keys [status corpo]} (admin-req! http-client token :post raiz payload base-url)]
            (exigir! status #{201} "falha ao criar o IdP gov.br" {:corpo corpo}))
      200 (let [{:keys [status corpo]} (admin-req! http-client token :put (str raiz "/" govbr-alias)
                                                   (merge corpo payload {:config (merge (:config corpo) (:config payload))})
                                                   base-url)]
            (exigir! status #{204} "falha ao atualizar o IdP gov.br" {:corpo corpo}))
      (exigir! status #{} "falha ao ler o IdP gov.br" {:corpo corpo}))
    (let [mapeadores (str raiz "/" govbr-alias "/mappers")
          desejados [{:name "username-govbr" :identityProviderAlias govbr-alias
                      :identityProviderMapper "oidc-username-idp-mapper"
                      :config {"template" (str "govbr-${CLAIM." claim-cpf "}") "syncMode" "INHERIT"}}
                     {:name "cpf" :identityProviderAlias govbr-alias
                      :identityProviderMapper "oidc-user-attribute-idp-mapper"
                      :config {"claim" claim-cpf "user.attribute" "govbr-sub" "syncMode" "FORCE"}}
                     {:name "nome" :identityProviderAlias govbr-alias
                      :identityProviderMapper "oidc-user-attribute-idp-mapper"
                      :config {"claim" "name" "user.attribute" "govbr-nome" "syncMode" "FORCE"}}
                     ;; firstName tambem: o perfil do Keycloak o exige da pessoa (sem ele, tela 'complete seu perfil')
                     {:name "primeiro-nome" :identityProviderAlias govbr-alias
                      :identityProviderMapper "oidc-user-attribute-idp-mapper"
                      :config {"claim" "name" "user.attribute" "firstName" "syncMode" "FORCE"}}]
          {:keys [status corpo]} (admin-req! http-client token :get mapeadores nil base-url)
          _ (exigir! status #{200} "falha ao ler os mappers do IdP gov.br" {})
          existentes (set (map :name (lista corpo)))]
      (doseq [m desejados :when (not (existentes (:name m)))]
        (let [{:keys [status corpo]} (admin-req! http-client token :post mapeadores m base-url)]
          (exigir! status #{201} "falha ao criar mapper do IdP gov.br" {:mapper (:name m) :corpo corpo}))))))

(defn- provisionar-realm-impl
  [{:keys [config http-client]} ente-id]
  (let [{:keys [base-url realm-prefixo audiencia web-client-id redirect-uris web-origins smtp govbr]} config
        govbr-cfg (when govbr (govbr-endpoints (merge govbr (select-keys config [:base-url :base-url-publico]))))
        realm (str realm-prefixo ente-id)
        token (admin-token! config http-client)
        {:keys [status]} (admin-req! http-client token :get (str "/admin/realms/" realm) nil base-url)]
    (when (= 404 status)
      (let [{:keys [status corpo]} (admin-req! http-client token :post "/admin/realms"
                                               {:realm realm :enabled true} base-url)]
        (when-not (= 201 status)
          (throw (ex-info "keycloak-idp: falha ao criar o realm (infra)" {:status status :corpo corpo})))))
    (declarar-atributo-identidade! http-client token base-url realm (some? govbr-cfg))
    (habilitar-passkey! http-client token base-url realm)
    (configurar-smtp! http-client token base-url realm smtp)
    (when govbr-cfg
      (garantir-fluxo-primeiro-login! http-client token base-url realm)
      (garantir-idp-govbr! http-client token base-url realm govbr-cfg))
    ;; Client de audiencia (API): valida o access-token; carrega o mapper de identidade-id + a audiencia propria.
    (garantir-client! http-client token base-url realm audiencia
                      {:clientId audiencia :publicClient true :standardFlowEnabled true
                       :directAccessGrantsEnabled false
                       :protocolMappers
                       (into [{:name "identidade-id" :protocol "openid-connect"
                               :protocolMapper "oidc-usermodel-attribute-mapper"
                               :config {"user.attribute" "identidade-id" "claim.name" "identidade-id"
                                        "jsonType.label" "String" "access.token.claim" "true"}}
                              {:name "audiencia-propria" :protocol "openid-connect"
                               :protocolMapper "oidc-audience-mapper"
                               :config {"included.client.audience" audiencia "access.token.claim" "true"}}]
                             mappers-de-sessao)})
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
                       (into [{:name "identidade-id" :protocol "openid-connect"
                               :protocolMapper "oidc-usermodel-attribute-mapper"
                               :config {"user.attribute" "identidade-id" "claim.name" "identidade-id"
                                        "jsonType.label" "String" "access.token.claim" "true"}}
                              {:name "audiencia-backend" :protocol "openid-connect"
                               :protocolMapper "oidc-audience-mapper"
                               :config {"included.client.audience" audiencia "access.token.claim" "true"}}]
                             mappers-de-sessao)})
    {:realm realm}))

(defn nome->first-last
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
      ;; :existia? diz a quem chama que o e-mail informado NAO foi gravado (o usuario ja' tinha o dele)
      {:keycloak-user-id (:id existente) :existia? true}
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
          {:keycloak-user-id (when (.isPresent location) (last (str/split (.get location) #"/"))) :existia? false})))))

(defn- corrigir-email-do-convite-impl
  "So' sem credencial (o convite nunca foi concluido): sem credencial nao ha' conta a tomar. Le o usuario INTEIRO e
  devolve o registro completo com o e-mail novo — com o perfil de usuario declarado, um PUT parcial pode apagar os
  atributos nao enviados (entre eles `identidade-id`, que o login usa). `emailVerified` volta a false: o resgate do
  convite e' a verificacao do e-mail novo."
  [{:keys [config http-client]} ente-id identidade-id email]
  (let [{:keys [base-url realm-prefixo]} config
        realm (str realm-prefixo ente-id)
        token (admin-token! config http-client)
        {kc-id :id} (or (buscar-usuario-por-identidade http-client token base-url realm identidade-id)
                        (throw (ex-info "keycloak-idp: usuario inexistente no realm" {:tipo :idp/usuario-inexistente})))
        caminho (str "/admin/realms/" realm "/users/" kc-id)
        {creds :corpo st-c :status} (admin-req! http-client token :get (str caminho "/credentials") nil base-url)]
    (when-not (= 200 st-c)
      (throw (ex-info "keycloak-idp: falha ao listar credenciais (infra)" {:status st-c})))
    (when (seq creds)
      (throw (ex-info "keycloak-idp: a pessoa ja' tem credencial — o e-mail e' trocado por ela" {:tipo :idp/conta-ja-ativa})))
    (let [{usuario :corpo st-u :status} (admin-req! http-client token :get caminho nil base-url)]
      (when-not (= 200 st-u)
        (throw (ex-info "keycloak-idp: falha ao ler o usuario (infra)" {:status st-u})))
      (let [{:keys [status corpo]} (admin-req! http-client token :put caminho
                                               (assoc usuario :email email :emailVerified false) base-url)]
        (cond
          (= 204 status) true
          (= 409 status) (throw (ex-info "keycloak-idp: e-mail ja' usado por outro usuario do realm"
                                         {:tipo :idp/email-em-uso}))
          :else (throw (ex-info "keycloak-idp: falha ao trocar o e-mail (infra)" {:status status :corpo corpo})))))))

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
  "PUT execute-actions-email: o KC gera o codigo de uso unico, envia ao e-mail institucional e, no resgate,
  OBRIGA o cadastro do passkey antes de qualquer acao. lifespan = janela do codigo (12h — cobre posse de
  legislatura em dia util sem virar credencial standing, §22.5.2 eixo F).
  `client-id` = `:web-client-id` do config (nao `:audiencia`): e' o client PKCE publico com redirectUris
  registrados, o mesmo que o navegador usa no login normal — o link do e-mail precisa de um client com
  destino de redirect valido; `:audiencia` (client de API, sem redirectUris) nao serve pra isso. O brief
  original citava um `:client-id` que nao existe no config.edn (so' ha' `:audiencia`/`:web-client-id`)."
  [{:keys [config http-client]} ente-id identidade-id]
  (let [{:keys [base-url realm-prefixo web-client-id]} config
        realm (str realm-prefixo ente-id)
        token (admin-token! config http-client)
        usuario (buscar-usuario-por-identidade http-client token base-url realm identidade-id)]
    (when-not usuario
      (throw (ex-info "keycloak-idp: usuario inexistente no realm — nao ha' quem convidar"
                      {:tipo :idp/usuario-inexistente})))
    (let [{:keys [status corpo]}
          (admin-req! http-client token :put
                      (str "/admin/realms/" realm "/users/" (:id usuario)
                           "/execute-actions-email?client_id=" web-client-id "&lifespan=43200")
                      ["webauthn-register-passwordless"]
                      base-url)]
      (when-not (= 204 status)
        (throw (ex-info "keycloak-idp: falha ao enviar convite (infra)" {:status status :corpo corpo})))
      true)))

(defn- apagar-realm-impl
  "ADR-0018 (Eixo 4.5): DELETE do realm da Casa encerrada. 204 = apagado; 404 = ja' nao existia (retomada do
  apagamento: o passo e' idempotente). Qualquer outro status LANCA — o apagamento marca o realm como pendente."
  [{:keys [config http-client]} ente-id]
  (let [{:keys [base-url realm-prefixo]} config
        realm (str realm-prefixo ente-id)
        token (admin-token! config http-client)
        {:keys [status corpo]} (admin-req! http-client token :delete (str "/admin/realms/" realm) nil base-url)]
    (case (long status)
      204 {:realm realm :existia? true}
      404 {:realm realm :existia? false}
      (throw (ex-info "keycloak-idp: falha ao apagar o realm (infra)" {:status status :corpo corpo :realm realm})))))

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
  (corrigir-email-do-convite! [this ente-id identidade-id email]
    (corrigir-email-do-convite-impl this ente-id identidade-id email))
  (resetar-mfa! [this ente-id identidade-id] (resetar-mfa-impl this ente-id identidade-id))
  (apagar-realm! [this ente-id] (apagar-realm-impl this ente-id)))

(defn keycloak-idp
  "Cria o Component KeycloakIdp (NAO-iniciado — chame component/start). `config` = o mapa `:keycloak` do
  config.edn. `jwks-provider-fn` e' opcional — (fn [config iss] -> JwkProvider); default =
  `jwks-provider-http` (real, HTTP). Testes injetam uma fake sem rede (inversao de dependencia, §5 do
  design)."
  ([config] (keycloak-idp config jwks-provider-http))
  ([config jwks-provider-fn]
   (map->KeycloakIdp {:config config :jwks-provider-fn jwks-provider-fn})))
