(ns oplenario.keycloak.operacao-test
  "INTEGRACAO gated (Keycloak + Mailpit reais) — ADR-0016: o realm do OPERADOR. (1) Provisionar converge o realm
  (idempotente) com senha + chave fisica obrigatorias, chave de seguranca (cross-platform) e sem grant direto de
  senha. (2) O convite sai por e-mail. (3) SO' A SENHA NAO BASTA: o navegador que acerta a senha cai na tela da
  chave, nunca no redirect com o code. A volta com a chave (WebAuthn) e' provada no e2e de navegador, que tem
  autenticador virtual."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.stuartsierra.component :as component]
            [jsonista.core :as json]
            [keycloak-admin :as kc-admin]
            [oplenario.admin-sistema.components.idp-admin :as op]
            [oplenario.config :as config])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)))

(def ^:private cfg (let [c (config/carregar)]
                     ;; o "navegador" do teste fala com a mesma URL do backend
                     (assoc (:operacao c) :base-url-publico (get-in c [:operacao :base-url]))))

(defn- mailpit-url [] (or (System/getenv "MAILPIT_URL") "http://mailpit:8025"))
(defn- http [] (HttpClient/newHttpClient))

(defn- mailpit! [metodo]
  (let [r (.send (http) (-> (HttpRequest/newBuilder (URI/create (str (mailpit-url) "/api/v1/messages")))
                            (.method metodo (HttpRequest$BodyPublishers/noBody)) (.build))
                 (HttpResponse$BodyHandlers/ofString))]
    (when (= "GET" metodo) (json/read-value (.body r) json/keyword-keys-object-mapper))))

(defn- admin [metodo caminho]
  (let [t (kc-admin/admin-token! (http) (:base-url cfg) (:admin-usuario cfg) (:admin-senha cfg))
        r (.send (http) (-> (HttpRequest/newBuilder (URI/create (str (:base-url cfg) "/admin/realms/" (:realm cfg) caminho)))
                            (.header "Authorization" (str "Bearer " t))
                            (.method metodo (HttpRequest$BodyPublishers/noBody)) (.build))
                 (HttpResponse$BodyHandlers/ofString))]
    (json/read-value (.body r) json/keyword-keys-object-mapper)))

;; navegador minimo com pote de cookies por caminho (o KC marca os cookies Secure; o de dev devolve em http)
(defn- ir! [{:keys [^HttpClient cli pote]} url & [form]]
  (let [cs (->> @pote (filter (fn [[[_ c] _]] (str/starts-with? (.getPath (URI/create url)) c)))
                (map (fn [[[k _] v]] (str k "=" v))) (str/join "; "))
        b (cond-> (HttpRequest/newBuilder (URI/create url)) (seq cs) (.header "Cookie" cs))
        b (if form
            (-> b (.header "Content-Type" "application/x-www-form-urlencoded")
                (.POST (HttpRequest$BodyPublishers/ofString
                        (str/join "&" (map (fn [[k v]] (str (URLEncoder/encode k "UTF-8") "=" (URLEncoder/encode v "UTF-8"))) form)))))
            (.GET b))
        r (.send cli (.build b) (HttpResponse$BodyHandlers/ofString))]
    (doseq [c (.allValues (.headers r) "set-cookie")]
      (let [[par & attrs] (str/split c #";\s*") [k v] (str/split par #"=" 2)
            caminho (or (some #(second (re-matches #"(?i)path=(.*)" %)) attrs) "/")]
        (if (str/blank? v) (swap! pote dissoc [k caminho]) (swap! pote assoc [k caminho] v))))
    {:status (.statusCode r) :corpo (.body r)
     :location (let [l (.firstValue (.headers r) "location")] (when (.isPresent l) (.get l)))}))

(defn- seguir! [nav r]
  (loop [r r n 0]
    (cond (> n 10) r
          (and (:location r) (not (str/includes? (:location r) "code="))) (recur (ir! nav (:location r)) (inc n))
          :else r)))

(deftest realm-do-operador-exige-a-chave-fisica
  (let [idp (component/start (op/keycloak-operacao cfg))
        oid (random-uuid) email (str "op-" oid "@oplenario.dev")]
    (try
      (op/provisionar-realm-operacao! idp)
      (op/provisionar-realm-operacao! idp)   ; converge sem quebrar
      (testing "a politica do realm"
        (let [realm (admin "GET" "")]
          (is (= op/fluxo-navegador (:browserFlow realm)))
          (is (= "cross-platform" (:webAuthnPolicyAuthenticatorAttachment realm)))
          (is (false? (:registrationAllowed realm)))
          (is (true? (:bruteForceProtected realm)))))
      (testing "o fluxo: senha E chave, ambos obrigatorios"
        (let [execs (admin "GET" (str "/authentication/flows/" op/fluxo-navegador "/executions"))
              req (into {} (map (juxt #(or (:providerId %) (:displayName %)) :requirement)) execs)]
          (is (= "REQUIRED" (get req "auth-username-password-form")))
          (is (= "REQUIRED" (get req "webauthn-authenticator")))))
      (testing "nenhum client aceita senha direta"
        (doseq [c (admin "GET" "/clients?clientId=oplenario-console")]
          (is (false? (:directAccessGrantsEnabled c)))))
      (testing "o convite chega por e-mail"
        (mailpit! "DELETE")
        (op/criar-operador-no-idp! idp {:operador-id oid :email email :nome "Operadora Teste"})
        (op/criar-operador-no-idp! idp {:operador-id oid :email email :nome "Operadora Teste"})   ; idempotente
        (is (true? (op/convidar-operador! idp oid)))
        (is (= [email] (map #(-> % :To first :Address) (:messages (mailpit! "GET"))))))
      (testing "so' a senha nao da' token: acertar a senha leva a tela da chave"
        (let [u (first (admin "GET" (str "/users?q=operador-id:" oid)))
              t (kc-admin/admin-token! (http) (:base-url cfg) (:admin-usuario cfg) (:admin-senha cfg))
              _ (kc-admin/setar-senha! (http) (:base-url cfg) t (:realm cfg) (:id u) "Senha-do-operador-2026")
              _ (kc-admin/limpar-required-actions! (http) (:base-url cfg) t (:realm cfg) (:id u))
              nav {:cli (-> (HttpClient/newBuilder) (.followRedirects HttpClient$Redirect/NEVER) (.build)) :pote (atom {})}
              redirect (first (:redirect-uris cfg))
              tela (seguir! nav (ir! nav (str (:base-url cfg) "/realms/" (:realm cfg) "/protocol/openid-connect/auth"
                                              "?client_id=" (:client-id cfg) "&response_type=code&scope=openid"
                                              "&redirect_uri=" (URLEncoder/encode redirect "UTF-8")
                                              "&state=s&code_challenge=" (apply str (repeat 43 "a"))
                                              "&code_challenge_method=S256")))
              acao (some-> (re-find #"<form[^>]*id=\"kc-form-login\"[^>]*action=\"([^\"]+)\"" (:corpo tela)) second
                           (str/replace "&amp;" "&"))
              depois (seguir! nav (ir! nav acao {"username" email "password" "Senha-do-operador-2026"}))]
          (is (some? acao) "a tela de senha do realm do operador")
          (is (not (str/includes? (str (:location depois)) "code=")) "a senha sozinha NAO devolve o code")
          (is (re-find #"registerByWebAuthn|authenticateByWebAuthn" (str (:corpo depois)))
              "e sim a cerimonia WebAuthn (registrar a chave, na primeira vez)")
          (is (str/includes? (str (:corpo depois)) "authenticatorAttachment : 'cross-platform'")
              "pedindo chave de seguranca, nao o autenticador do aparelho")))
      (finally
        (op/desligar-operador-no-idp! idp oid)
        (component/stop idp)))))
