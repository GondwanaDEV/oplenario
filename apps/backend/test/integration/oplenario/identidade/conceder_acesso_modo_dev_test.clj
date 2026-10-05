(ns oplenario.identidade.conceder-acesso-modo-dev-test
  "INTEGRACAO (PG real + borda HTTP): em modo dev (APP_ENV=dev, sem Keycloak) o `admin_ente` concede acesso em
  /administracao e a rota respondia 500 DEPOIS de gravar o vinculo: o idp-dev lancava em `provisionar-realm!`. Agora o
  IdP que o host liga em dev (`sistema/idp-para`, a mesma decisao que liga o login por token de dev) pula o
  provisionamento de forma explicita — nao cria conta nem senha — e a rota devolve o mesmo 201 de producao.
  Producao nao muda: fora de dev/test o host liga o KeycloakIdp, e Keycloak fora do ar continua 500
  (`acesso-http-test/conceder-acesso-keycloak-fora-do-ar-500`)."
  (:require [oplenario.suporte-cpf :refer [cpf-valido]]
            [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo]
            [oplenario.identidade.db.identidade :as id]
            [oplenario.identidade.diplomat.http.in :as identidade-in]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp :as idp]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.migracao :as migracao]
            [oplenario.sistema :as sistema]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- repo-id [] (assoc (repo/repositorio) :datasource {:ds *ds*}))

(defn- identidade! [nome] (id/inserir! *ds* {:id (random-uuid) :cpf (cpf-valido) :nome nome}))

(defn- admin! [ente]
  (let [iid (identidade! "Administradora")]
    (repo/conceder-acesso! (repo-id) ente {:id (random-uuid) :ente-id ente :identidade-id iid :tipo "admin_ente"
                                           :estado "ativo"}
                           ["admin_ente"])
    iid))

(defn- idp-do-modo-dev
  "O IdP que o HOST liga com APP_ENV=dev — nao um fake do teste: o que esta' sob prova e' a escolha do host."
  []
  (#'sistema/idp-para {:env "dev"}))

(defn- servico []
  (let [r (repo-id) idp-comp (idp-do-modo-dev)]
    (-> (http/servico (config/carregar)
                      (identidade-in/rotas {:auth (it/autenticacao idp-comp r) :repo-identidade r :idp idp-comp})
                      it/globais)
        ph/create-server ::ph/service-fn)))

(defn- bearer [ente iid]
  (str "Bearer " (json/write-value-as-string {:sub "u" :ente-id (str ente) :identidade-id (str iid)})))

(defn- json-de [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(defn- conceder [svc ente adm corpo]
  (pt/response-for svc :post "/identidade/acessos"
                   :headers {"Content-Type" "application/json" "Authorization" (bearer ente adm)}
                   :body (json/write-value-as-string corpo)))

(deftest modo-dev-conceder-acesso-a-vereador-devolve-201-e-a-pessoa-entra
  (let [svc (servico) ente (random-uuid) adm (admin! ente) iid (identidade! "Vera Vereadora")
        r (conceder svc ente adm {:identidade-id (str iid) :tipo "vereador" :papeis ["vereador"]
                                  :email "vera@camara.gov.br"})]
    (is (= 201 (:status r)) "o mesmo sucesso de producao, nao 500 depois de gravar")
    (is (= {:convite "enviado" :email "novo"} (select-keys (json-de r) [:convite :email])) "o mesmo corpo de producao")
    (is (some? (parse-uuid (str (:vinculo-id (json-de r))))))
    (is (= #{"vereador"} (repo/papeis-de (repo-id) ente iid)) "o vinculo e o papel estao gravados")
    (is (= 200 (:status (pt/response-for svc :get "/meu/identidade" :headers {"Authorization" (bearer ente iid)})))
        "e em dev a pessoa ja' entra pelo token de dev")))

(deftest modo-dev-conceder-auditor-e-reconceder-tambem-dao-201
  (let [svc (servico) ente (random-uuid) adm (admin! ente) iid (identidade! "Otto Auditor")
        corpo {:identidade-id (str iid) :tipo "servidor" :papeis ["auditor"] :email "otto@camara.gov.br"}]
    (is (= 201 (:status (conceder svc ente adm corpo))))
    (is (= 201 (:status (conceder svc ente adm corpo))) "repetir continua idempotente em dev")
    (is (= #{"auditor"} (repo/papeis-de (repo-id) ente iid)))))

(deftest modo-dev-reenviar-convite-devolve-200
  (let [svc (servico) ente (random-uuid) adm (admin! ente) iid (identidade! "Rita")]
    (conceder svc ente adm {:identidade-id (str iid) :tipo "vereador" :papeis ["vereador"] :email "rita@camara.gov.br"})
    (let [r (pt/response-for svc :post (str "/identidade/acessos/" iid "/convite")
                             :headers {"Authorization" (bearer ente adm)})]
      (is (= 200 (:status r)) "antes: o idp-dev lancava :idp/nao-suportado e a borda respondia 500")
      (is (= {:convite "reenviado"} (json-de r))))))

(deftest o-idp-de-dev-nao-cria-conta-nem-senha
  (let [i (idp-do-modo-dev) ente (random-uuid) iid (random-uuid)]
    (is (= {:existia? false :provisionamento :pulado-em-dev}
           (idp/criar-usuario! i ente {:identidade-id iid :nome "X" :email "x@camara.gov.br"}))
        "nada de :keycloak-user-id: nenhuma conta foi criada")
    (is (= {:provisionamento :pulado-em-dev} (idp/provisionar-realm! i ente)))
    (is (= {:provisionamento :pulado-em-dev} (idp/convidar! i ente iid)))))

(deftest o-idp-dev-cru-segue-recusando-o-provisionamento
  ;; o construtor cru (o dos testes que so' precisam do token) continua lancando: quem pula e' a escolha do HOST para
  ;; o modo dev, nunca um default silencioso.
  (is (thrown? Exception (idp/provisionar-realm! (idp-dev/idp-dev) (random-uuid))))
  (is (thrown? Exception (idp/convidar! (idp-dev/idp-dev) (random-uuid) (random-uuid)))))

(deftest fora-de-dev-o-host-liga-o-keycloak
  (doseq [env ["production" "staging" nil "" "producton"]]
    (is (instance? oplenario.kernel.components.keycloak_idp.KeycloakIdp
                   (#'sistema/idp-para {:env env :keycloak {:url "http://kc"}}))
        (str "env " (pr-str env) ": nada de pular provisionamento"))))
