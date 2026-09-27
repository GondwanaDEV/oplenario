(ns oplenario.admin-sistema.components.idp-admin
  "O IdP do OPERADOR da plataforma (ADR-0016, §22.5.1, §22.9 Eixo 6): um realm proprio (`operacao`), num Keycloak
  que em producao e' FISICAMENTE SEPARADO do das Casas — a separacao e' config de deploy (`:operacao :base-url`);
  em dev/CI o mesmo container serve os dois. O token deste realm nao carrega Casa nenhuma: carrega o
  `operador-id`, e so' o `admin_sistema` o aceita.

  Login do operador = senha + CHAVE FISICA (WebAuthn de dois fatores, anexacao `cross-platform`: a chave de
  seguranca USB/NFC, nao o autenticador embutido do aparelho). `:aaguids` restringe a modelos de chave
  aceitos quando a operacao definir a frota. Sem grant direto de senha: so' o fluxo do navegador.

  Reusa a mecanica do kernel (`keycloak-idp`: JWKS com allowlist de issuer, admin-API, clients, SMTP) — so' o
  desenho do realm e' daqui."
  (:require [clojure.string :as str]
            [com.stuartsierra.component :as component]
            [jsonista.core :as json]
            [oplenario.kernel.components.keycloak-idp :as kc])
  (:import (java.net.http HttpClient)
           (java.util UUID)))

(defprotocol IdpOperacao
  (verificar-token-operador [this token]
    "Token do realm do operador -> {:sub :operador-id :exp} | nil (fail-closed). Infra da JWKS propaga.")
  (provisionar-realm-operacao! [this] "Cria/converge o realm do operador (idempotente).")
  (criar-operador-no-idp! [this operador] "{:operador-id :email :nome} -> usuario no realm (idempotente).")
  (convidar-operador! [this operador-id] "E-mail do Keycloak: definir a senha e registrar a chave fisica.")
  (desligar-operador-no-idp! [this operador-id] "Desabilita o usuario e derruba as sessoes dele no realm."))

(def fluxo-navegador "operacao-navegador")
(def ^:private subfluxo-formulario "operacao-senha-e-chave")

;; ---------------------------------------------------------------------------------------------
;; Verificacao
;; ---------------------------------------------------------------------------------------------

(defn emissores
  "Os issuers aceitos: o realm do operador na URL interna e na publica (split-horizon, mesmo racional do kernel)."
  [{:keys [base-url base-url-publico realm]}]
  (->> [base-url base-url-publico] (remove str/blank?) distinct (mapv #(str % "/realms/" realm))))

(defn verificar-token*
  [{:keys [config] :as comp} token]
  (let [aceitos (set (emissores config))]
    (kc/verificar-jwt comp aceitos (:audiencia config) token
      (fn [verificado _iss]
        (when-let [oid (kc/claim-str verificado "operador-id")]
          {:sub (.getSubject verificado)
           :operador-id (UUID/fromString oid)
           :exp (some-> verificado .getExpiresAtAsInstant .getEpochSecond int)})))))

;; ---------------------------------------------------------------------------------------------
;; Realm
;; ---------------------------------------------------------------------------------------------

(defn politica-do-realm
  "Os atributos do realm que fazem dele o realm do operador (pura; o teste le' daqui)."
  [{:keys [realm aaguids sessao atestacao]}]
  (cond-> {:realm realm :enabled true
           :displayName "O Plenário · Operação"
           :registrationAllowed false :resetPasswordAllowed false :rememberMe false
           :loginWithEmailAllowed true :duplicateEmailsAllowed false
           :bruteForceProtected true :failureFactor 5 :permanentLockout false
           :passwordPolicy "length(14) and notUsername and notEmail and passwordHistory(5)"
           :ssoSessionIdleTimeout (* 60 (or (:ociosa-min sessao) 15))
           :ssoSessionMaxLifespan (* 3600 (or (:absoluta-h sessao) 8))
           :webAuthnPolicyRpEntityName "O Plenário · Operação"
           :webAuthnPolicyAuthenticatorAttachment "cross-platform"
           ;; `direct` (padrao): o Keycloak confere a cadeia de certificados da chave contra o truststore dele — em producao
           ;; o truststore PRECISA ter as raizes FIDO dos modelos aceitos, senao nenhuma chave registra (falha fechada).
           ;; `none` so' em dev/demo/CI (chave virtual, sem raiz confiavel).
           :webAuthnPolicyAttestationConveyancePreference (or atestacao "direct")
           :webAuthnPolicyUserVerificationRequirement "preferred"
           :webAuthnPolicyRequireResidentKey "No"
           :webAuthnPolicyAvoidSameAuthenticatorRegister true
           :browserFlow fluxo-navegador}
    (seq aaguids) (assoc :webAuthnPolicyAcceptableAaguids (vec aaguids))))

(defn- declarar-atributo-operador! [http token base-url realm]
  (let [{:keys [status corpo]} (kc/admin-req! http token :get (str "/admin/realms/" realm "/users/profile") nil base-url)
        _ (kc/exigir! status #{200} "falha ao ler o user-profile do realm do operador" {})]
    (when-not (some #(= "operador-id" (:name %)) (:attributes corpo))
      (let [novo (update corpo :attributes conj {:name "operador-id" :displayName "Operador (operador-id)"
                                                 :multivalued false :validations {}
                                                 :permissions {:view ["admin"] :edit ["admin"]}})
            {:keys [status corpo]} (kc/admin-req! http token :put (str "/admin/realms/" realm "/users/profile") novo base-url)]
        (kc/exigir! status #{200} "falha ao declarar operador-id" {:corpo corpo})))))

(defn- habilitar-registro-de-chave! [http token base-url realm]
  (let [{:keys [status corpo]}
        (kc/admin-req! http token :put (str "/admin/realms/" realm "/authentication/required-actions/webauthn-register")
                       {:alias "webauthn-register" :name "Webauthn Register" :providerId "webauthn-register"
                        :enabled true :defaultAction false :priority 25 :config {}}
                       base-url)]
    (kc/exigir! status #{204} "falha ao habilitar o registro de chave" {:corpo corpo})))

(defn- execucoes [http token base-url realm fluxo]
  (let [{:keys [status corpo]} (kc/admin-req! http token :get
                                              (str "/admin/realms/" realm "/authentication/flows/" fluxo "/executions")
                                              nil base-url)]
    (kc/exigir! status #{200} "falha ao ler as execucoes do fluxo" {:fluxo fluxo})
    (kc/lista corpo)))

(defn- exigir-requisito! [http token base-url realm fluxo pred requisito]
  (when-let [e (some #(when (pred %) %) (execucoes http token base-url realm fluxo))]
    (when-not (= requisito (:requirement e))
      (let [{:keys [status corpo]} (kc/admin-req! http token :put
                                                  (str "/admin/realms/" realm "/authentication/flows/" fluxo "/executions")
                                                  (assoc e :requirement requisito) base-url)]
        (kc/exigir! status #{202 204} "falha ao ajustar o requisito da execucao" {:corpo corpo})))))

(defn- garantir-fluxo-navegador!
  "cookie (ALTERNATIVE) | subfluxo (ALTERNATIVE) = senha (REQUIRED) + chave fisica (REQUIRED). Quem ainda nao tem
  chave registrada e' levado a registrar antes de entrar (o WebAuthn Authenticator pede a required action)."
  [http token base-url realm]
  (let [raiz (str "/admin/realms/" realm "/authentication/flows")
        {:keys [status corpo]} (kc/admin-req! http token :get raiz nil base-url)
        _ (kc/exigir! status #{200} "falha ao listar fluxos" {})]
    (when-not (some #(= fluxo-navegador (:alias %)) (kc/lista corpo))
      (let [{:keys [status corpo]} (kc/admin-req! http token :post raiz
                                                  {:alias fluxo-navegador :providerId "basic-flow" :topLevel true
                                                   :builtIn false
                                                   :description "Operador: senha + chave fisica (ADR-0016)"}
                                                  base-url)]
        (kc/exigir! status #{201} "falha ao criar o fluxo do operador" {:corpo corpo})))
    (let [execs (str raiz "/" fluxo-navegador "/executions")
          atuais (execucoes http token base-url realm fluxo-navegador)]
      (when-not (some #(= "auth-cookie" (:providerId %)) atuais)
        (let [{:keys [status corpo]} (kc/admin-req! http token :post (str execs "/execution") {:provider "auth-cookie"}
                                                    base-url)]
          (kc/exigir! status #{201} "falha ao incluir o cookie" {:corpo corpo})))
      (when-not (some #(= subfluxo-formulario (:displayName %)) atuais)
        (let [{:keys [status corpo]} (kc/admin-req! http token :post (str execs "/flow")
                                                    {:alias subfluxo-formulario :type "basic-flow"
                                                     :provider "registration-page-form"
                                                     :description "senha + chave fisica"}
                                                    base-url)]
          (kc/exigir! status #{201} "falha ao criar o subfluxo" {:corpo corpo}))))
    (let [sub-execs (str raiz "/" subfluxo-formulario "/executions")]
      (doseq [p ["auth-username-password-form" "webauthn-authenticator"]]
        (when-not (some #(= p (:providerId %)) (execucoes http token base-url realm subfluxo-formulario))
          (let [{:keys [status corpo]} (kc/admin-req! http token :post (str sub-execs "/execution") {:provider p} base-url)]
            (kc/exigir! status #{201} "falha ao incluir execucao no subfluxo" {:provider p :corpo corpo})))))
    (exigir-requisito! http token base-url realm fluxo-navegador #(= "auth-cookie" (:providerId %)) "ALTERNATIVE")
    (exigir-requisito! http token base-url realm fluxo-navegador #(= subfluxo-formulario (:displayName %)) "ALTERNATIVE")
    (exigir-requisito! http token base-url realm subfluxo-formulario #(= "auth-username-password-form" (:providerId %))
                       "REQUIRED")
    (exigir-requisito! http token base-url realm subfluxo-formulario #(= "webauthn-authenticator" (:providerId %))
                       "REQUIRED")))

(defn- mappers [audiencia]
    [{:name "operador-id" :protocol "openid-connect" :protocolMapper "oidc-usermodel-attribute-mapper"
      :config {"user.attribute" "operador-id" "claim.name" "operador-id" "jsonType.label" "String"
               "access.token.claim" "true"}}
     {:name "audiencia-operacao" :protocol "openid-connect" :protocolMapper "oidc-audience-mapper"
      :config {"included.client.audience" audiencia "access.token.claim" "true"}}])

(defn- provisionar-realm-impl [{:keys [config http-client]}]
  (let [{:keys [base-url realm audiencia client-id redirect-uris web-origins smtp]} config
        http http-client
        token (kc/admin-token! config http)
        {:keys [status]} (kc/admin-req! http token :get (str "/admin/realms/" realm) nil base-url)]
    (when (= 404 status)
      (let [{:keys [status corpo]} (kc/admin-req! http token :post "/admin/realms" {:realm realm :enabled true} base-url)]
        (kc/exigir! status #{201} "falha ao criar o realm do operador" {:corpo corpo})))
    (declarar-atributo-operador! http token base-url realm)
    (habilitar-registro-de-chave! http token base-url realm)
    (garantir-fluxo-navegador! http token base-url realm)
    ;; a politica (e o browserFlow) vem DEPOIS do fluxo existir: o KC recusa apontar para um fluxo inexistente
    (let [{:keys [status corpo]} (kc/admin-req! http token :put (str "/admin/realms/" realm) (politica-do-realm config)
                                                base-url)]
      (kc/exigir! status #{204} "falha ao aplicar a politica do realm do operador" {:corpo corpo}))
    (when smtp (kc/configurar-smtp! http token base-url realm smtp))
    (kc/garantir-client! http token base-url realm audiencia
                         {:clientId audiencia :publicClient true :standardFlowEnabled false
                          :directAccessGrantsEnabled false :protocolMappers (mappers audiencia)})
    (kc/garantir-client! http token base-url realm client-id
                         {:clientId client-id :publicClient true :standardFlowEnabled true
                          :directAccessGrantsEnabled false :implicitFlowEnabled false
                          :redirectUris redirect-uris :webOrigins web-origins
                          :attributes {"pkce.code.challenge.method" "S256"}
                          :protocolMappers (mappers audiencia)})
    {:realm realm}))

(defn- buscar-usuario [http token {:keys [base-url realm]} operador-id]
  (let [{:keys [status corpo]} (kc/admin-req! http token :get
                                              (str "/admin/realms/" realm "/users?q=operador-id:" operador-id)
                                              nil base-url)]
    (kc/exigir! status #{200} "falha ao buscar o operador no realm" {})
    (first corpo)))

(defn- criar-operador-impl [{:keys [config http-client]} {:keys [operador-id email nome]}]
  (let [token (kc/admin-token! config http-client)]
    (if-let [u (buscar-usuario http-client token config operador-id)]
      {:keycloak-user-id (:id u)}
      (let [[primeiro ultimo] (kc/nome->first-last nome)
            {:keys [status corpo headers]}
            (kc/admin-req! http-client token :post (str "/admin/realms/" (:realm config) "/users")
                           {:username email :email email :enabled true :emailVerified true
                            :firstName primeiro :lastName ultimo
                            :requiredActions ["UPDATE_PASSWORD" "webauthn-register"]
                            :attributes {:operador-id [(str operador-id)]}}
                           (:base-url config))]
        (kc/exigir! status #{201} "falha ao criar o operador no realm" {:corpo corpo})
        (let [loc (.firstValue headers "location")]
          {:keycloak-user-id (when (.isPresent loc) (last (str/split (.get loc) #"/")))})))))

(defn- convidar-impl [{:keys [config http-client]} operador-id]
  (let [token (kc/admin-token! config http-client)
        u (or (buscar-usuario http-client token config operador-id)
              (throw (ex-info "operador sem usuario no realm" {:tipo :idp/usuario-inexistente})))
        {:keys [status corpo]} (kc/admin-req! http-client token :put
                                              (str "/admin/realms/" (:realm config) "/users/" (:id u)
                                                   "/execute-actions-email?client_id=" (:client-id config)
                                                   "&lifespan=43200")
                                              ["UPDATE_PASSWORD" "webauthn-register"] (:base-url config))]
    (kc/exigir! status #{204} "falha ao enviar o convite do operador" {:corpo corpo})
    true))

(defn- desligar-impl [{:keys [config http-client]} operador-id]
  (let [token (kc/admin-token! config http-client)]
    (when-let [u (buscar-usuario http-client token config operador-id)]
      (let [base (str "/admin/realms/" (:realm config) "/users/" (:id u))
            {:keys [status corpo]} (kc/admin-req! http-client token :put base (assoc u :enabled false) (:base-url config))]
        (kc/exigir! status #{204} "falha ao desabilitar o operador" {:corpo corpo})
        (let [{:keys [status corpo]} (kc/admin-req! http-client token :post (str base "/logout") nil (:base-url config))]
          (kc/exigir! status #{204} "falha ao derrubar as sessoes do operador" {:corpo corpo}))))
    true))

(defrecord KeycloakOperacao [config jwks-provider-fn jwks-cache http-client]
  component/Lifecycle
  (start [this] (if http-client this (assoc this :jwks-cache (atom {}) :http-client (HttpClient/newHttpClient))))
  (stop [this] (assoc this :jwks-cache nil :http-client nil))

  IdpOperacao
  (verificar-token-operador [this token] (verificar-token* this token))
  (provisionar-realm-operacao! [this] (provisionar-realm-impl this))
  (criar-operador-no-idp! [this o] (criar-operador-impl this o))
  (convidar-operador! [this id] (convidar-impl this id))
  (desligar-operador-no-idp! [this id] (desligar-impl this id)))

(defn keycloak-operacao
  "`config` = o mapa `:operacao` do config. `jwks-provider-fn` injetavel (testes sem rede)."
  ([config] (keycloak-operacao config kc/jwks-provider-http))
  ([config jwks-provider-fn] (map->KeycloakOperacao {:config config :jwks-provider-fn jwks-provider-fn})))

;; ---------------------------------------------------------------------------------------------
;; Dev/teste: token = JSON de claims, SEM assinatura (como o idp-dev das Casas). NUNCA fora de dev/test.
;; ---------------------------------------------------------------------------------------------

(defrecord IdpOperacaoDev []
  IdpOperacao
  (verificar-token-operador [_ token]
    (try
      (let [c (json/read-value token json/keyword-keys-object-mapper)]
        (when (and (map? c) (string? (:operador-id c)))
          {:sub (str (:sub c)) :operador-id (UUID/fromString (:operador-id c))}))
      (catch Exception _ nil)))
  (provisionar-realm-operacao! [_] (throw (ex-info "idp-operacao-dev: sem realm" {:tipo :idp/nao-suportado})))
  (criar-operador-no-idp! [_ _] (throw (ex-info "idp-operacao-dev: sem realm" {:tipo :idp/nao-suportado})))
  (convidar-operador! [_ _] (throw (ex-info "idp-operacao-dev: sem realm" {:tipo :idp/nao-suportado})))
  (desligar-operador-no-idp! [_ _] true))

(defn idp-operacao-dev [] (->IdpOperacaoDev))
