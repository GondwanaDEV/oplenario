(ns govbr-simulado
  "O gov.br SIMULADO (ADR-0015): um realm `govbr-simulado` no proprio Keycloak, que faz o papel do Login Unico em
  dev, demo e CI — o fake nao espera o credenciamento no gov.br. Emite o CPF na claim `cpf` (o sub de usuario do
  Keycloak e' um UUID; no gov.br de verdade o sub JA' e' o CPF — `keycloak-idp/govbr-endpoints` diz qual claim ler)
  e o nome em `name`. O client `oplenario-broker` (confidential, segredo fixo de dev) e' o que o realm de cada Casa
  usa como IdP quando GOVBR_AMBIENTE=simulado.

  Tudo idempotente (GET-then-create), falha alto em erro de infra. NAO e' codigo de producao: vive em demo/ (no
  classpath dos testes e da semente), nunca em src/."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [keycloak-admin :as kc-admin]
            [oplenario.config :as config])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)))

(def realm "govbr-simulado")

(defn- req! [^HttpClient http token metodo url corpo]
  (let [b (-> (HttpRequest/newBuilder) (.uri (URI/create url))
              (.header "Authorization" (str "Bearer " token)) (.header "Content-Type" "application/json"))
        b (case metodo
            :get (.GET b)
            :post (.POST b (HttpRequest$BodyPublishers/ofString (json/write-value-as-string corpo)))
            :put (.PUT b (HttpRequest$BodyPublishers/ofString (json/write-value-as-string corpo))))
        resp (.send http (.build b) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp)
     :corpo (let [s (.body resp)] (when (seq s) (json/read-value s json/keyword-keys-object-mapper)))}))

(defn- exigir! [{:keys [status corpo]} esperados msg]
  (when-not (contains? esperados status)
    (throw (ex-info (str "govbr-simulado: " msg) {:status status :corpo corpo}))))

(defn garantir!
  "Cria (ou confere) o realm simulado, o atributo `cpf`, o client do broker e o mapper da claim `cpf`. `kc` = o mapa
  `:keycloak` da config. As URLs de retorno aceitas sao as do broker de QUALQUER realm do mesmo Keycloak (dev)."
  [{:keys [base-url base-url-publico admin-usuario admin-senha]}]
  (let [http (HttpClient/newHttpClient)
        tok (kc-admin/admin-token! http base-url admin-usuario admin-senha)
        adm (str base-url "/admin/realms")]
    (when (= 404 (:status (req! http tok :get (str adm "/" realm) nil)))
      (exigir! (req! http tok :post adm {:realm realm :enabled true :displayName "gov.br (simulado)"}) #{201}
               "falha ao criar o realm"))
    (let [{:keys [corpo] :as r} (req! http tok :get (str adm "/" realm "/users/profile") nil)]
      (exigir! r #{200} "falha ao ler o user-profile")
      (when-not (some #(= "cpf" (:name %)) (:attributes corpo))
        (exigir! (req! http tok :put (str adm "/" realm "/users/profile")
                       (update corpo :attributes conj {:name "cpf" :displayName "CPF" :multivalued false
                                                       :permissions {:view ["admin"] :edit ["admin"]}
                                                       :validations {}}))
                 #{200} "falha ao declarar o atributo cpf")))
    (let [retornos (->> [base-url base-url-publico] (remove str/blank?) distinct (mapv #(str % "/realms/*")))
          {:keys [corpo] :as r} (req! http tok :get (str adm "/" realm "/clients?clientId=" config/govbr-simulado-client-id) nil)
          _ (exigir! r #{200} "falha ao ler o client")
          payload {:clientId config/govbr-simulado-client-id :publicClient false :standardFlowEnabled true
                   :directAccessGrantsEnabled false :secret config/govbr-simulado-client-secret
                   :redirectUris retornos
                   :protocolMappers [{:name "cpf" :protocol "openid-connect"
                                      :protocolMapper "oidc-usermodel-attribute-mapper"
                                      :config {"user.attribute" "cpf" "claim.name" "cpf" "jsonType.label" "String"
                                               "id.token.claim" "true" "access.token.claim" "true"
                                               "userinfo.token.claim" "true"}}]}]
      (if-let [existente (first corpo)]
        (exigir! (req! http tok :put (str adm "/" realm "/clients/" (:id existente))
                       (merge existente (select-keys payload [:redirectUris :secret])))
                 #{204} "falha ao atualizar o client")
        (exigir! (req! http tok :post (str adm "/" realm "/clients") payload) #{201} "falha ao criar o client")))
    realm))

(defn garantir-cidadao!
  "Uma pessoa no gov.br simulado: username = CPF, senha fixa, nome civil. Idempotente por username."
  [{:keys [base-url admin-usuario admin-senha]} {:keys [cpf nome senha]}]
  (let [http (HttpClient/newHttpClient)
        tok (kc-admin/admin-token! http base-url admin-usuario admin-senha)
        adm (str base-url "/admin/realms/" realm)
        existente (first (:corpo (req! http tok :get (str adm "/users?exact=true&username=" cpf) nil)))
        [primeiro ultimo] (let [p (str/split (str/trim nome) #"\s+" 2)] (if (= 2 (count p)) p [(first p) (first p)]))
        id (or (:id existente)
               (do (exigir! (req! http tok :post (str adm "/users")
                                  {:username cpf :enabled true :firstName primeiro :lastName ultimo
                                   :email (str cpf "@govbr-simulado.local") :emailVerified true
                                   :attributes {:cpf [cpf]}})
                            #{201} "falha ao criar a pessoa")
                   (:id (first (:corpo (req! http tok :get (str adm "/users?exact=true&username=" cpf) nil))))))]
    (kc-admin/setar-senha! http base-url tok realm id senha)
    id))
