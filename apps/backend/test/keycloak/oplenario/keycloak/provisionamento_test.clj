(ns oplenario.keycloak.provisionamento-test
  "INTEGRACAO gated: exige o Keycloak do docker (--profile auth) rodando. Prova provisionar-realm!/
  criar-usuario!/resetar-mfa! contra a admin-API real — nao roda no CI (sem Keycloak la'; ver
  tests.edn/:keycloak e ci.yml --skip :keycloak)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.kernel.components.idp :as idp]
            [oplenario.kernel.components.keycloak-idp :as kc])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)))

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

;; ---------------------------------------------------------------------------------------------
;; realm-habilita-passkey-e-smtp (Task 2) — inspecao CRUA da realm representation via admin-API.
;; Nao existe (e nao deve existir) port pra isto no protocolo IdentityProvider: e' so' verificacao de
;; teste, no mesmo espirito do `apagar-realm-teste!` de ponta_a_ponta_test.clj.
;; ---------------------------------------------------------------------------------------------

(defn- http! [] (HttpClient/newHttpClient))

(defn- admin-token-teste! [^HttpClient http kc-base-url]
  (let [corpo "grant_type=password&client_id=admin-cli&username=admin&password=admin"
        req (-> (HttpRequest/newBuilder)
                (.uri (URI/create (str kc-base-url "/realms/master/protocol/openid-connect/token")))
                (.header "Content-Type" "application/x-www-form-urlencoded")
                (.POST (HttpRequest$BodyPublishers/ofString corpo))
                (.build))
        resp (.send http req (HttpResponse$BodyHandlers/ofString))]
    (:access_token (json/read-value (.body resp) json/keyword-keys-object-mapper))))

(defn- admin-get-teste! [^HttpClient http token kc-base-url caminho]
  (let [req (-> (HttpRequest/newBuilder)
                (.uri (URI/create (str kc-base-url caminho)))
                (.header "Authorization" (str "Bearer " token))
                (.build))
        resp (.send http req (HttpResponse$BodyHandlers/ofString))]
    (json/read-value (.body resp) json/keyword-keys-object-mapper)))

(defn- admin-put-teste! [^HttpClient http token kc-base-url caminho corpo]
  (let [req (-> (HttpRequest/newBuilder)
                (.uri (URI/create (str kc-base-url caminho)))
                (.header "Authorization" (str "Bearer " token))
                (.header "Content-Type" "application/json")
                (.PUT (HttpRequest$BodyPublishers/ofString (json/write-value-as-string corpo)))
                (.build))]
    (.send http req (HttpResponse$BodyHandlers/ofString))))

(defn- desabilitar-passkey-teste!
  "PUT cru que forca a required action de passkey de volta a `enabled:false` — usado SO' pra provar
  causalidade em `realm-habilita-passkey-e-smtp` (ver comentario la'): sem isto, checar `enabled:true`
  depois de `provisionar-realm!` provaria apenas o default de fabrica do Keycloak (que ja nasce
  habilitado nesta imagem), nao o efeito do nosso `habilitar-passkey!`."
  [http token kc-base-url realm]
  (admin-put-teste! http token kc-base-url
                     (str "/admin/realms/" realm "/authentication/required-actions/webauthn-register-passwordless")
                     {:alias "webauthn-register-passwordless" :name "Webauthn Register Passwordless"
                      :providerId "webauthn-register-passwordless" :enabled false :defaultAction false
                      :priority 30 :config {}}))

(defn- realm-representation
  "GET cru da RealmRepresentation do KC p/ o realm do `ente-id` — le' o base-url/realm-prefixo do PROPRIO
  config do `idp` (nao do base-url do modulo de teste), pra' inspecionar exatamente o realm que
  `provisionar-realm!` acabou de tocar. A RealmRepresentation raiz (GET /admin/realms/:realm) NAO carrega
  `requiredActions` (confirmado contra o Keycloak 26 vivo — so' `smtpServer` e' visivel ali); a lista de
  required actions mora num endpoint PROPRIO (GET .../authentication/required-actions), entao esta funcao
  busca os dois e junta num so' mapa, pro chamador nao ter de saber dessa divisao de endpoint do KC."
  [idp ente-id]
  (let [{kc-base-url :base-url realm-prefixo :realm-prefixo} (:config idp)
        realm (str realm-prefixo ente-id)
        http (http!)
        token (admin-token-teste! http kc-base-url)
        realm-rep (admin-get-teste! http token kc-base-url (str "/admin/realms/" realm))
        required-actions (admin-get-teste! http token kc-base-url
                                           (str "/admin/realms/" realm "/authentication/required-actions"))]
    (assoc realm-rep :requiredActions required-actions)))

(deftest realm-habilita-passkey-e-smtp
  (let [ente (random-uuid)
        idp (component/start (kc/keycloak-idp (:keycloak (config/carregar))))]
    (try
      (idp/provisionar-realm! idp ente)
      ;; Prova de causalidade: o default de fabrica desta required action VARIA por versao/modo de import
      ;; do Keycloak (nesta imagem, 26.0.0 start-dev, ela ja nasce enabled:true) — entao so' checar
      ;; enabled:true agora provaria o default do servidor, nao o efeito do NOSSO codigo (passaria
      ;; identico com `habilitar-passkey!` deletado). Forca-se o estado OPOSTO por fora e chama-se
      ;; `provisionar-realm!` de novo (idempotente) — se ele flipar de volta pra true, e' o nosso PUT.
      (let [{kc-base-url :base-url realm-prefixo :realm-prefixo} (:config idp)
            realm (str realm-prefixo ente)
            http (http!)
            token (admin-token-teste! http kc-base-url)]
        (desabilitar-passkey-teste! http token kc-base-url realm))
      (idp/provisionar-realm! idp ente)
      (let [r (realm-representation idp ente)]
        (is (true? (->> (:requiredActions r)
                        (filter #(= "webauthn-register-passwordless" (:alias %)))
                        first :enabled))
            "provisionar-realm! precisa AFIRMAR o estado habilitado, independente do default do KC —
             provado aqui forcando o oposto por fora antes de chamar de novo (idempotente); senao marcar
             o usuario com a required action seria silenciosamente ignorado")
        (is (= "mailpit" (get-in r [:smtpServer :host]))
            "realm aponta p/ o servidor de e-mail — quem envia o convite e' o KC, nao a app"))
      (finally (component/stop idp)))))
