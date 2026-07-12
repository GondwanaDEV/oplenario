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
