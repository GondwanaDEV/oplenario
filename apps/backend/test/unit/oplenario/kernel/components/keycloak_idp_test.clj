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
   :web-origins ["http://localhost:3000"]
   :smtp {:host "mailpit" :port 1025 :from "nao-responda@oplenario.local"
          :ssl false :starttls false :auth false}})

(defn- fake-admin-req!
  "Fake de `admin-req!` — captura toda chamada em `chamadas` (atom, vetor de {:metodo :caminho :corpo}) e
  responde por convencao: GET raiz do realm (`/admin/realms/<realm>`, sem sufixo) -> 200 (realm ja
  existe, pula a criacao); PUT nessa mesma raiz (configurar-smtp!) -> 204 (convencao real do KC, ver
  Task 2); GET .../users/profile -> 200 com identidade-id JA declarado (pula o PUT); GET
  .../clients?clientId=X -> 200 com [] (nao existe) ou [{...}] (existe), conforme `clients-existentes`
  (set de clientId); POST -> 201; PUT generico (ex.: habilitar-passkey!) -> 204 — status REAIS
  confirmados empiricamente contra o Keycloak 26 vivo (curl direto), nao um chute de #{200 204}."
  [chamadas clients-existentes]
  (fn [_http-client _token metodo caminho corpo-map _base-url]
    (swap! chamadas conj {:metodo metodo :caminho caminho :corpo corpo-map})
    (cond
      (str/ends-with? caminho "/users/profile")
      {:status 200 :corpo {:attributes [{:name "identidade-id"}]}}

      ;; ADR-0025: as prioridades padrao do Keycloak 26 (o codigo antes da senha)
      (and (= metodo :get) (str/ends-with? caminho "/required-actions/UPDATE_PASSWORD"))
      {:status 200 :corpo {:alias "UPDATE_PASSWORD" :priority 30}}
      (and (= metodo :get) (str/ends-with? caminho "/required-actions/CONFIGURE_TOTP"))
      {:status 200 :corpo {:alias "CONFIGURE_TOTP" :priority 10}}

      (re-matches #"/admin/realms/[^/]+" caminho)
      (if (= metodo :get) {:status 200 :corpo {}} {:status 204 :corpo {}})

      (str/includes? caminho "/clients?clientId=")
      (let [client-id (subs caminho (+ (str/index-of caminho "clientId=") (count "clientId=")))]
        (if (contains? clients-existentes client-id)
          {:status 200 :corpo [{:id "existing-id" :clientId client-id :attributes {"outro-atributo" "valor-antigo"}}]}
          {:status 200 :corpo []}))

      (= metodo :post) {:status 201 :corpo {}}
      (= metodo :put)  {:status 204 :corpo {}}
      :else            {:status 200 :corpo {}})))

(defn- fake-admin-req-com-falha!
  "Como `fake-admin-req!`, mas o PUT cujo caminho bate em `caminho-alvo?` devolve uma falha de infra
  (status 400) em vez do sucesso convencional — usado p/ provar que `habilitar-passkey!`/
  `configurar-smtp!` PROPAGAM o erro (LANCAM) em vez de engolir e seguir em frente com o realm
  parcialmente configurado."
  [caminho-alvo?]
  (fn [_http-client _token metodo caminho _corpo-map _base-url]
    (cond
      (and (= metodo :put) (caminho-alvo? caminho))
      {:status 400 :corpo {:erro "falha simulada"}}

      (str/ends-with? caminho "/users/profile")
      {:status 200 :corpo {:attributes [{:name "identidade-id"}]}}

      ;; ADR-0025: as prioridades padrao do Keycloak 26 (o codigo antes da senha)
      (and (= metodo :get) (str/ends-with? caminho "/required-actions/UPDATE_PASSWORD"))
      {:status 200 :corpo {:alias "UPDATE_PASSWORD" :priority 30}}
      (and (= metodo :get) (str/ends-with? caminho "/required-actions/CONFIGURE_TOTP"))
      {:status 200 :corpo {:alias "CONFIGURE_TOTP" :priority 10}}

      (re-matches #"/admin/realms/[^/]+" caminho)
      (if (= metodo :get) {:status 200 :corpo {}} {:status 204 :corpo {}})

      (str/includes? caminho "/clients?clientId=") {:status 200 :corpo []}

      (= metodo :post) {:status 201 :corpo {}}
      (= metodo :put)  {:status 204 :corpo {}}
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
      (is (seq (:redirectUris (:corpo post-web))) "redirectUris nao-vazio"))
    (testing "o token PKCE tem de carregar identidade-id + a audiencia do backend (achado T17): sem estes
              mappers o verificar-token do backend (.withAudience + claim identidade-id) rejeita o token
              do login real -> 'token invalido' no mint"
      (let [mappers (:protocolMappers (:corpo post-web))
            por-mapper (into {} (map (juxt :protocolMapper identity) mappers))
            ;; por NOME: ha' mais de um mapper de atributo desde o gov.br (identidade-id e govbr-sub, ADR-0015)
            id-mapper (some #(when (= "identidade-id" (:name %)) %) mappers)
            aud-mapper (get por-mapper "oidc-audience-mapper")]
        (is (some? id-mapper) "esperava o mapper de atributo identidade-id no client web")
        (is (= "identidade-id" (get-in id-mapper [:config "claim.name"])))
        (is (= "true" (get-in id-mapper [:config "access.token.claim"])))
        (is (some? aud-mapper) "esperava o mapper de audiencia no client web")
        (is (= "oplenario-backend" (get-in aud-mapper [:config "included.client.audience"]))
            "a audiencia injetada tem de ser a do backend (:audiencia da config)")))))

(deftest client-web-aceita-voltar-ao-sistema-depois-do-logout
  ;; O "Sair" manda `post_logout_redirect_uri` = a origem do app, sem `id_token_hint`. Sem o atributo, o Keycloak
  ;; ignora o destino e termina em "Voce saiu da sessao", sem caminho de volta (visto em producao, 05/10/2026).
  (testing "client novo: o atributo lista a origem do redirect de login, com e sem a barra final"
    (let [post-web (post-do-client (provisionar-capturando! #{}) "oplenario-web")]
      (is (= "http://localhost:3000##http://localhost:3000/"
             (get-in post-web [:corpo :attributes "post.logout.redirect.uris"])))))
  (testing "client que ja' existe: o PUT de convergencia leva o atributo, sem perder os outros"
    (let [chamadas (provisionar-capturando! #{"oplenario-web" "oplenario-backend"})
          put-web (some #(when (and (= :put (:metodo %)) (str/ends-with? (:caminho %) "/clients/existing-id")
                                    (= "oplenario-web" (get-in % [:corpo :clientId])))
                           %)
                        chamadas)]
      (is (some? put-web) "esperava o PUT de convergencia do client web")
      (is (= "http://localhost:3000##http://localhost:3000/"
             (get-in put-web [:corpo :attributes "post.logout.redirect.uris"])))
      (is (= "valor-antigo" (get-in put-web [:corpo :attributes "outro-atributo"]))
          "atributo que o Keycloak ja' tinha nao some no PUT"))))

(deftest destinos-pos-logout-sai-da-origem-dos-redirects-de-login
  (is (= "https://oplenario.calvetec.com.br##https://oplenario.calvetec.com.br/"
         (kc/destinos-pos-logout ["https://oplenario.calvetec.com.br/api/auth/callback"])))
  (is (= "http://localhost:3000##http://localhost:3000/##https://a.b:8443##https://a.b:8443/"
         (kc/destinos-pos-logout ["http://localhost:3000/api/auth/callback" "http://localhost:3000/outro"
                                  "https://a.b:8443/api/auth/callback"]))
      "origem repetida entra uma vez; a porta fica")
  (is (= "" (kc/destinos-pos-logout ["nao e' url" "/relativo"])) "o que nao e' URL absoluta nao entra"))

(deftest provisionar-realm-idempotente-nao-recria-client-web
  (let [chamadas (provisionar-capturando! #{"oplenario-web" "oplenario-backend"})]
    (testing "client oplenario-web ja existe -> nenhum POST de criacao"
      (is (nil? (post-do-client chamadas "oplenario-web"))))))

;; ---------------------------------------------------------------------------------------------
;; habilitar-passkey!/configurar-smtp! (Task 2, fix wave) — prova de CAUSALIDADE + falha de infra.
;;
;; O Keycloak 26.0.0 vivo (start-dev) ja nasce com `webauthn-register-passwordless` `enabled:true` por
;; default (achado real, ver task-2-report.md) — entao um teste de integracao que so' checa
;; `:enabled true` DEPOIS de `provisionar-realm!` provaria um default do KC, nao o efeito do nosso codigo
;; (passaria identico com `habilitar-passkey!` deletado). Aqui, em vez disso, capturamos as chamadas
;; que `provisionar-realm-impl` de fato emite e afirmamos o PUT + o corpo desejado diretamente —
;; deterministico, nao depende de nenhum default mutavel de servidor.
;; ---------------------------------------------------------------------------------------------

(defn- put-cujo-caminho-termina-em [chamadas sufixo]
  (some #(when (and (= :put (:metodo %)) (str/ends-with? (:caminho %) sufixo)) %) chamadas))

(defn- put-na-raiz-do-realm [chamadas]
  (some #(when (and (= :put (:metodo %)) (re-matches #"/admin/realms/[^/]+" (:caminho %))) %) chamadas))

(deftest provisionar-realm-emite-put-habilitando-a-required-action-de-passkey
  (let [chamadas (provisionar-capturando! #{})
        put-passkey (put-cujo-caminho-termina-em
                     chamadas "/authentication/required-actions/webauthn-register-passwordless")]
    (is (some? put-passkey) "esperava um PUT na required-action de passkey")
    (is (true? (:enabled (:corpo put-passkey)))
        "o PUT tem de afirmar o estado habilitado, independente do default de fabrica do KC")))

(deftest provisionar-realm-emite-put-configurando-o-smtp-do-realm
  (let [chamadas (provisionar-capturando! #{})
        put-smtp (put-na-raiz-do-realm chamadas)]
    (is (some? put-smtp) "esperava um PUT na raiz do realm com smtpServer")
    (is (= "mailpit" (get-in put-smtp [:corpo :smtpServer :host]))
        "realm aponta p/ o servidor de e-mail configurado — quem envia o convite e' o KC, nao a app")))

(deftest habilitar-passkey-com-falha-de-infra-lanca
  (with-redefs-fn {#'kc/admin-token! (fn [_config _http-client] "fake-token")
                    #'kc/admin-req!  (fake-admin-req-com-falha!
                                       #(str/ends-with? % "/authentication/required-actions/webauthn-register-passwordless"))}
    (fn []
      (is (thrown? clojure.lang.ExceptionInfo
                   (#'kc/provisionar-realm-impl {:config config-provisionamento :http-client nil} ente-id))
          "PUT que falha ao habilitar a required action de passkey tem de LANCAR — erro de infra nunca
           segue em frente com o realm parcialmente configurado"))))

(deftest configurar-smtp-com-falha-de-infra-lanca
  (with-redefs-fn {#'kc/admin-token! (fn [_config _http-client] "fake-token")
                    #'kc/admin-req!  (fake-admin-req-com-falha!
                                       #(re-matches #"/admin/realms/[^/]+" %))}
    (fn []
      (is (thrown? clojure.lang.ExceptionInfo
                   (#'kc/provisionar-realm-impl {:config config-provisionamento :http-client nil} ente-id))
          "PUT que falha ao configurar o SMTP do realm tem de LANCAR — erro de infra nunca segue em
           frente com o realm parcialmente configurado"))))

;; ---------------------------------------------------------------------------------------------
;; gov.br (ADR-0015): as claims do login pelo gov.br e o provisionamento do broker no realm da Casa
;; ---------------------------------------------------------------------------------------------

(deftest token-do-govbr-traz-idp-cpf-e-nome
  (let [ip (idp-com (fn [_ _] (provider-fixo (jwk-de pub kid))))
        tok (-> (JWT/create) (.withIssuer ^String iss) (.withSubject "kc-user") (.withKeyId ^String kid)
                (.withAudience (into-array String [audiencia]))
                (.withClaim "idp" "govbr") (.withClaim "govbr-sub" "52998224725") (.withClaim "govbr-nome" "Maria Cidadã")
                (.withExpiresAt (.plusSeconds (Instant/now) 60))
                (.sign (Algorithm/RSA256 pub ^RSAPrivateKey priv)))
        claims (idp/verificar-token ip tok)]
    (is (= "govbr" (:idp claims)))
    (is (= "52998224725" (:govbr-sub claims)))
    (is (= "Maria Cidadã" (:nome claims)))
    (is (nil? (:identidade-id claims)))
    (is (= ente-id (:ente-id claims)))))

(deftest token-institucional-nao-traz-idp
  (let [ip (idp-com (fn [_ _] (provider-fixo (jwk-de pub kid))))
        claims (idp/verificar-token ip (token-valido))]
    (is (nil? (:idp claims)))
    (is (nil? (:govbr-sub claims)))))

(deftest govbr-endpoints-por-ambiente
  (testing "producao e homologacao: as URLs do Login Unico, o sub e' o CPF"
    (let [p (kc/govbr-endpoints {:ambiente "producao" :client-id "c" :client-secret "s"})]
      (is (= "https://sso.acesso.gov.br/authorize" (:url-autorizacao p)))
      (is (= "https://sso.acesso.gov.br/token" (:url-token p)))
      (is (= "https://sso.acesso.gov.br/jwk" (:url-jwks p)))
      (is (= "sub" (:claim-cpf p))))
    (is (= "https://sso.staging.acesso.gov.br/authorize"
           (:url-autorizacao (kc/govbr-endpoints {:ambiente "homologacao"})))))
  (testing "simulado: um realm no proprio Keycloak — o navegador pela URL publica, o broker pela interna"
    (let [s (kc/govbr-endpoints {:ambiente "simulado" :base-url "http://keycloak:8080"
                                 :base-url-publico "http://localhost:8090"})]
      (is (= "http://localhost:8090/realms/govbr-simulado/protocol/openid-connect/auth" (:url-autorizacao s)))
      (is (= "http://keycloak:8080/realms/govbr-simulado/protocol/openid-connect/token" (:url-token s)))
      (is (= "cpf" (:claim-cpf s)))))
  (is (nil? (kc/govbr-endpoints nil)) "sem config -> sem gov.br")
  (is (thrown? clojure.lang.ExceptionInfo (kc/govbr-endpoints {:ambiente "outro"}))))

(defn- provisionar-com-govbr! []
  (let [chamadas (atom [])
        base (fake-admin-req! chamadas #{})]
    (with-redefs-fn {#'kc/admin-token! (fn [_config _http-client] "fake-token")
                     #'kc/admin-req! (fn [h t metodo caminho corpo b]
                                       (if (and (= :get metodo) (str/ends-with? caminho "/identity-provider/instances/govbr"))
                                         (do (swap! chamadas conj {:metodo metodo :caminho caminho}) {:status 404 :corpo nil})
                                         (base h t metodo caminho corpo b)))}
      (fn [] (#'kc/provisionar-realm-impl
              {:config (assoc config-provisionamento
                              :govbr {:ambiente "producao" :client-id "cid" :client-secret "seg"})
               :http-client nil}
              ente-id)))
    @chamadas))

(deftest provisionar-realm-com-govbr-cria-o-broker
  (let [chamadas (provisionar-com-govbr!)
        post-de (fn [sufixo] (filter #(and (= :post (:metodo %)) (str/ends-with? (:caminho %) sufixo)) chamadas))
        idp-post (first (post-de "/identity-provider/instances"))
        cfg (get-in idp-post [:corpo :config])]
    (testing "o IdP gov.br: OIDC + PKCE S256, escondido da tela de login institucional, 1o login so' cria"
      (is (= "govbr" (get-in idp-post [:corpo :alias])))
      (is (= "govbr-primeiro-login" (get-in idp-post [:corpo :firstBrokerLoginFlowAlias])))
      (is (false? (get-in idp-post [:corpo :trustEmail])))
      (is (= "true" (get cfg "pkceEnabled")))
      (is (= "S256" (get cfg "pkceMethod")))
      (is (= "true" (get cfg "hideOnLoginPage")))
      (is (= "client_secret_basic" (get cfg "clientAuthMethod")))
      (is (= "cid" (get cfg "clientId"))))
    (testing "mappers do IdP: username govbr-<cpf>, CPF -> govbr-sub, nome -> govbr-nome; nada de e-mail"
      (let [mappers (map :corpo (post-de "/identity-provider/instances/govbr/mappers"))
            por-attr (into {} (map (juxt #(get-in % [:config "user.attribute"]) identity)) mappers)]
        (is (some #(= "govbr-${CLAIM.sub}" (get-in % [:config "template"])) mappers))
        (is (= "sub" (get-in (por-attr "govbr-sub") [:config "claim"])))
        (is (= "name" (get-in (por-attr "govbr-nome") [:config "claim"])) "o nome como o gov.br mandou")
        (is (= "name" (get-in (por-attr "firstName") [:config "claim"])) "e o firstName que o perfil exige")
        (is (nil? (por-attr "email")))))
    (testing "o fluxo de 1o login so' tem 'criar se unico' — nao ha' como vincular a conta existente"
      (is (seq (post-de "/authentication/flows")))
      (is (some #(= "idp-create-user-if-unique" (get-in % [:corpo :provider]))
                (post-de "/authentication/flows/govbr-primeiro-login/executions/execution"))))
    (testing "os clients emitem govbr-sub e o IdP da sessao (idp)"
      (let [web (post-do-client chamadas "oplenario-web")
            nomes (set (map :name (:protocolMappers (:corpo web))))]
        (is (contains? nomes "govbr-sub"))
        (is (contains? nomes "govbr-nome"))
        (is (contains? nomes "idp"))))
    (testing "o atributo govbr-sub e' declarado so' p/ admin"
      (let [perfil (some #(when (and (= :put (:metodo %)) (str/ends-with? (:caminho %) "/users/profile")) %) chamadas)
            attr (some #(when (= "govbr-sub" (:name %)) %) (get-in perfil [:corpo :attributes]))]
        (is (= {:view ["admin"] :edit ["admin"]} (:permissions attr)))))))

(deftest provisionar-realm-sem-govbr-nao-mexe-em-identity-provider
  (let [chamadas (provisionar-capturando! #{})]
    (is (not-any? #(str/includes? (:caminho %) "identity-provider") chamadas))))

;; ---------------------------------------------------------------------------------------------
;; ADR-0018 (Eixo 4.5): apagar o realm da Casa encerrada — o inverso de provisionar-realm!.
;; ---------------------------------------------------------------------------------------------

(defn- apagar-com-status! [status]
  (let [chamadas (atom [])]
    [(with-redefs-fn {#'kc/admin-token! (fn [_config _http-client] "fake-token")
                       #'kc/admin-req!   (fn [_h _t metodo caminho _c _b]
                                           (swap! chamadas conj [metodo caminho])
                                           {:status status :corpo nil})}
       (fn [] (#'kc/apagar-realm-impl {:config config-provisionamento :http-client nil} ente-id)))
     @chamadas]))

(deftest apagar-realm-faz-delete-no-realm-da-casa
  (let [[r chamadas] (apagar-com-status! 204)]
    (is (= [[:delete (str "/admin/realms/" realm-prefixo ente-id)]] chamadas))
    (is (= {:realm (str realm-prefixo ente-id) :existia? true} r))))

(deftest apagar-realm-inexistente-e-ok-retomada
  (is (= {:realm (str realm-prefixo ente-id) :existia? false} (first (apagar-com-status! 404)))))

(deftest apagar-realm-falha-de-infra-lanca
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"falha ao apagar o realm" (apagar-com-status! 500))))
