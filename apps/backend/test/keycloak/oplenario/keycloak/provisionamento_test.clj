(ns oplenario.keycloak.provisionamento-test
  "INTEGRACAO gated: exige o Keycloak do docker (--profile auth) rodando. Prova provisionar-realm!/
  criar-usuario!/resetar-mfa! contra a admin-API real — nao roda no CI (sem Keycloak la'; ver
  tests.edn/:keycloak e ci.yml --skip :keycloak)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
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
   :admin-usuario "admin" :admin-senha "admin" :jwks-cache-ttl-s 600 :tema-login "oplenario"})

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
;; teste, no mesmo espirito do `apagar-realm-teste!` de ponta_a_ponta_test.clj (o `idp/apagar-realm!` e' o apagamento
;; de uma Casa encerrada, ADR-0018 — nao a limpeza de teste).
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

;; ---------------------------------------------------------------------------------------------
;; criar-usuario-idempotente-e-exige-passkey (Task 3) — GET cru do usuario pelo mesmo `q=identidade-id:`
;; que `buscar-usuario-por-identidade` usa em producao. Ao contrario da RealmRepresentation (ver
;; `realm-representation` acima), a UserRepresentation JA carrega `requiredActions`/`emailVerified` num
;; unico GET — sem divisao de endpoint.
;; ---------------------------------------------------------------------------------------------

(defn- usuario-representation
  "GET cru do usuario do `ente-id` pela `identidade-id`, mesma query (`q=identidade-id:`) que
  `buscar-usuario-por-identidade` usa em producao. Le' base-url/realm-prefixo do PROPRIO config do
  `idp` (mesmo racional de `realm-representation`)."
  [idp ente-id identidade-id]
  (let [{kc-base-url :base-url realm-prefixo :realm-prefixo} (:config idp)
        realm (str realm-prefixo ente-id)
        http (http!)
        token (admin-token-teste! http kc-base-url)]
    (first (admin-get-teste! http token kc-base-url
                             (str "/admin/realms/" realm "/users?q=identidade-id:" identidade-id)))))

(deftest criar-usuario-idempotente-e-exige-senha-e-codigo
  (let [ente (random-uuid) ident (random-uuid)
        _ (idp/provisionar-realm! *idp* ente)
        u1 (idp/criar-usuario! *idp* ente {:identidade-id ident :nome "Helena Matos"
                                           :email "helena@camara.local"})
        u2 (idp/criar-usuario! *idp* ente {:identidade-id ident :nome "Helena Matos"
                                           :email "helena@camara.local"})]
    (is (= (:keycloak-user-id u1) (:keycloak-user-id u2))
        "get-or-create: re-provisionar devolve o MESMO usuario (hoje lanca em != 201)")
    (let [r (usuario-representation *idp* ente ident)]
      (is (= #{"UPDATE_PASSWORD" "CONFIGURE_TOTP"} (set (:requiredActions r)))
          "ADR-0025: nasce obrigado a criar a senha e o segundo fator (codigo) antes de qualquer acao — o login da Casa
          e' CPF + senha + codigo; so' passkey deixava a pessoa sem senha para entrar")
      (is (false? (:emailVerified r))
          "emailVerified=true era [GAP] por nao haver SMTP; agora ha' — o KC verifica de verdade"))))

;; ---------------------------------------------------------------------------------------------
;; corrigir-email-do-convite! (opcao A, 05/10/2026): o reconvite de quem nunca entrou vai para o e-mail novo.
;; ---------------------------------------------------------------------------------------------
(defn- usuario-kc [ente-id iid]
  (let [http (:http-client *idp*)
        token (kc/admin-token! config http)
        realm (str "ente-" ente-id)
        {:keys [corpo]} (kc/admin-req! http token :get (str "/admin/realms/" realm "/users?username=" iid "&exact=true")
                                       nil base-url)]
    (first corpo)))

(deftest corrigir-email-troca-so-o-email-e-preserva-o-identidade-id
  (let [ente-id (random-uuid) iid (random-uuid)]
    (idp/provisionar-realm! *idp* ente-id)
    (is (false? (:existia? (idp/criar-usuario! *idp* ente-id {:identidade-id iid :nome "Helena Matos"
                                                               :email "errado@example.com"}))))
    (is (true? (:existia? (idp/criar-usuario! *idp* ente-id {:identidade-id iid :nome "Helena Matos"
                                                              :email "certo@example.com"})))
        "a segunda vez acha o usuario e avisa que o e-mail informado nao foi gravado")
    (is (= "errado@example.com" (:email (usuario-kc ente-id iid))))
    (is (true? (idp/corrigir-email-do-convite! *idp* ente-id iid "certo@example.com")))
    (let [u (usuario-kc ente-id iid)]
      (is (= "certo@example.com" (:email u)))
      (is (= [(str iid)] (get-in u [:attributes :identidade-id]))
          "o atributo que o login usa continua la' (o PUT leva o usuario inteiro)")
      (is (= #{"UPDATE_PASSWORD" "CONFIGURE_TOTP"} (set (:requiredActions u)))
          "a senha e o codigo do primeiro acesso continuam exigidos (ADR-0025)"))))

(deftest corrigir-email-recusa-quem-ja-tem-credencial
  (let [ente-id (random-uuid) iid (random-uuid)]
    (idp/provisionar-realm! *idp* ente-id)
    (let [{kc-id :keycloak-user-id} (idp/criar-usuario! *idp* ente-id {:identidade-id iid :nome "Ja Entrou"
                                                                        :email "dela@example.com"})
          http (:http-client *idp*)
          token (kc/admin-token! config http)]
      (kc/admin-req! http token :put (str "/admin/realms/ente-" ente-id "/users/" kc-id "/reset-password")
                     {:type "password" :value "Senha@123456" :temporary false} base-url)
      (is (= :idp/conta-ja-ativa
             (try (idp/corrigir-email-do-convite! *idp* ente-id iid "outro@example.com") nil
                  (catch clojure.lang.ExceptionInfo e (:tipo (ex-data e))))))
      (is (= "dela@example.com" (:email (usuario-kc ente-id iid))) "nada mudou"))))

(deftest corrigir-email-para-o-de-outra-pessoa-da-casa-lanca
  (let [ente-id (random-uuid) a (random-uuid) b (random-uuid)]
    (idp/provisionar-realm! *idp* ente-id)
    (idp/criar-usuario! *idp* ente-id {:identidade-id a :nome "Ana" :email "ana@example.com"})
    (idp/criar-usuario! *idp* ente-id {:identidade-id b :nome "Bia" :email "bia@example.com"})
    (is (= :idp/email-em-uso
           (try (idp/corrigir-email-do-convite! *idp* ente-id a "bia@example.com") nil
                (catch clojure.lang.ExceptionInfo e (:tipo (ex-data e))))))))

;; ---------------------------------------------------------------------------------------------
;; ADR-0025: o realm tem a cara da Casa (nome, pt-BR, tema do O Plenario) e se defende de forca bruta.
;; ---------------------------------------------------------------------------------------------

(deftest realm-nasce-com-o-nome-da-casa-em-portugues-e-com-o-tema
  (let [ente (random-uuid)]
    (idp/provisionar-realm! *idp* ente {:nome "Câmara Municipal de Teste"})
    (let [r (realm-representation *idp* ente)]
      (is (= "Câmara Municipal de Teste" (:displayName r)) "o titulo da tela de login e' o nome da Casa, nao o realm")
      (is (true? (:internationalizationEnabled r)))
      (is (= ["pt-BR"] (:supportedLocales r)))
      (is (= "pt-BR" (:defaultLocale r)))
      (is (= "oplenario" (:loginTheme r)) "o tema do O Plenario")
      (is (= "oplenario" (:emailTheme r)) "o convite sai com o texto do O Plenario")
      (is (true? (:bruteForceProtected r)) "senha errada demais trava a conta por um tempo")
      (is (= 10 (:failureFactor r)))
      (is (false? (:permanentLockout r)) "trava temporaria: quem sabe o CPF de alguem nao o tranca para sempre")
      (is (re-find #"length\(8\)" (str (:passwordPolicy r)))))))

(deftest sem-nome-o-realm-guarda-o-que-tinha
  (let [ente (random-uuid)]
    (idp/provisionar-realm! *idp* ente)
    (is (= "O Plenário" (:displayName (realm-representation *idp* ente))) "realm novo sem nome: o da plataforma")
    (idp/provisionar-realm! *idp* ente {:nome "Câmara Municipal de Russas"})
    (idp/provisionar-realm! *idp* ente)
    (is (= "Câmara Municipal de Russas" (:displayName (realm-representation *idp* ente)))
        "reprovisionar sem nome (ex.: conceder acesso) nao apaga o nome da Casa")))

(deftest desligar-o-tema-pela-config-volta-ao-padrao
  (let [ente (random-uuid)
        sem-tema (component/start (kc/keycloak-idp (assoc config :tema-login nil)))]
    (try
      (idp/provisionar-realm! *idp* ente {:nome "Câmara Municipal de Teste"})
      (is (= "oplenario" (:loginTheme (realm-representation *idp* ente))))
      (idp/provisionar-realm! sem-tema ente)
      (let [r (realm-representation sem-tema ente)]
        (is (str/blank? (:loginTheme r)) "KEYCLOAK_TEMA_LOGIN vazio + reaplicar = o login volta ao padrao do Keycloak")
        (is (str/blank? (:emailTheme r))))
      (finally (component/stop sem-tema)))))

(deftest o-client-web-aponta-para-a-entrada-do-o-plenario
  (let [ente (random-uuid)
        idp (component/start (kc/keycloak-idp (assoc config :web-client-id "oplenario-web"
                                                     :redirect-uris ["https://app.exemplo/api/auth/callback"]
                                                     :web-origins ["https://app.exemplo"])))]
    (try
      (idp/provisionar-realm! idp ente)
      (let [http (http!) token (admin-token-teste! http base-url)
            c (first (admin-get-teste! http token base-url
                                       (str "/admin/realms/ente-" ente "/clients?clientId=oplenario-web")))]
        (is (= "https://app.exemplo/entrar" (:baseUrl c))
            "o 'voltar ao aplicativo' do fim do convite leva a' entrada pelo CPF"))
      (finally (component/stop idp)))))

(deftest resetar-mfa-obriga-cadastrar-o-codigo-de-novo
  (let [ente (random-uuid) iid (random-uuid)]
    (idp/provisionar-realm! *idp* ente)
    (idp/criar-usuario! *idp* ente {:identidade-id iid :nome "Servidor Teste" :email "srv@example.com"})
    (let [u (usuario-representation *idp* ente iid)
          http (http!) token (admin-token-teste! http base-url)]
      ;; a pessoa ja' concluiu o convite (tem senha): sem pendencia
      (admin-put-teste! http token base-url (str "/admin/realms/ente-" ente "/users/" (:id u))
                        (assoc u :requiredActions []))
      (admin-put-teste! http token base-url (str "/admin/realms/ente-" ente "/users/" (:id u) "/reset-password")
                        {:type "password" :value "Senha-de-teste-1" :temporary false})
      (idp/resetar-mfa! *idp* ente iid)
      (let [depois (usuario-representation *idp* ente iid)]
        (is (= ["CONFIGURE_TOTP"] (:requiredActions depois))
            "sem o fator, o proximo login pede o cadastro do codigo — senao a pessoa entraria so' com a senha")
        (is (= (str iid) (first (get-in depois [:attributes :identidade-id])))
            "o PUT do usuario inteiro preserva o identidade-id (o login depende dele)")))))

(deftest o-primeiro-acesso-cria-a-senha-antes-do-codigo
  (let [ente (random-uuid)]
    (idp/provisionar-realm! *idp* ente)
    (let [prioridade (fn [alias] (->> (:requiredActions (realm-representation *idp* ente))
                                      (filter #(= alias (:alias %))) first :priority))]
      (is (< (prioridade "UPDATE_PASSWORD") (prioridade "CONFIGURE_TOTP"))
          "o Keycloak executa as acoes por prioridade: sem o ajuste, o convite pedia o codigo antes de a pessoa ter senha"))))

(deftest resetar-mfa-de-quem-nunca-teve-senha-pede-a-senha-tambem
  ;; quem concluiu o convite ANTIGO (so' passkey) nao tem senha: tirar a passkey sem pedir a senha o deixaria sem
  ;; como entrar (o login da Casa pede senha)
  (let [ente (random-uuid) iid (random-uuid)]
    (idp/provisionar-realm! *idp* ente)
    (idp/criar-usuario! *idp* ente {:identidade-id iid :nome "Do Convite Antigo" :email "antigo@example.com"})
    (let [u (usuario-representation *idp* ente iid)
          http (http!) token (admin-token-teste! http base-url)]
      (admin-put-teste! http token base-url (str "/admin/realms/ente-" ente "/users/" (:id u))
                        (assoc u :requiredActions []))
      (idp/resetar-mfa! *idp* ente iid)
      (is (= #{"UPDATE_PASSWORD" "CONFIGURE_TOTP"} (set (:requiredActions (usuario-representation *idp* ente iid))))))))
