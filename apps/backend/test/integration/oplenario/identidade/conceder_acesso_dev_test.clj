(ns oplenario.identidade.conceder-acesso-dev-test
  "INTEGRACAO (PG real + borda HTTP): conceder acesso com o IdP de DEV (`idp-dev`, o que a demo local e o CI usam).
  O handler grava o vinculo e o papel e SO' DEPOIS provisiona o usuario no IdP; o `idp-dev` LANCAVA nas operacoes
  de provisionamento, entao o ato ficava gravado e a resposta era 500 (a tela mostrava erro com o acesso ja' dado).
  Prova: com o `idp-dev` o ato fecha em 201 e repetir e' idempotente; e a falha de um IdP de verdade continua visivel
  (500, nunca engolida), com o ato ja' gravado e o reenvio consertando — o desenho banco-antes-do-IdP do handler."
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
            [oplenario.migracao :as migracao]))

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
    (repo/conceder-acesso! (repo-id) ente {:id (random-uuid) :ente-id ente :identidade-id iid :tipo "servidor"
                                           :estado "ativo"}
                           ["admin_ente"])
    iid))

(defn- servico [idp-comp]
  (let [r (repo-id) auth (it/autenticacao idp-comp r)]
    (-> (http/servico (config/carregar)
                      (identidade-in/rotas {:auth auth :repo-identidade r :idp idp-comp})
                      it/globais)
        ph/create-server ::ph/service-fn)))

(defn- bearer [ente iid]
  (str "Bearer " (json/write-value-as-string {:sub "u" :ente-id (str ente) :identidade-id (str iid)})))

(defn- conceder [svc ente adm alvo]
  (pt/response-for svc :post "/identidade/acessos"
                   :headers {"Content-Type" "application/json" "Authorization" (bearer ente adm)}
                   :body (json/write-value-as-string {:identidade-id (str alvo) :tipo "vereador"
                                                      :papeis ["vereador"] :email "helena@camara.local"})))

(defn- json-de [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(deftest idp-dev-conceder-acesso-fecha-em-201-e-repetir-e-idempotente
  (let [svc (servico (idp-dev/idp-dev)) ente (random-uuid) adm (admin! ente) alvo (identidade! "Helena Matos")]
    (let [r (conceder svc ente adm alvo)]
      (is (= 201 (:status r)) "com o IdP de dev o ato fecha: nao ha' realm a provisionar, e isso nao e' erro")
      (is (= "novo" (:email (json-de r)))))
    (is (= #{"vereador"} (repo/papeis-de (repo-id) ente alvo)) "o acesso foi concedido")
    (is (= 201 (:status (conceder svc ente adm alvo))) "repetir o mesmo ato nao quebra")
    (is (= #{"vereador"} (repo/papeis-de (repo-id) ente alvo)) "e nao duplica o papel")))

(defn- idp-que-falha-em
  "O IdP de dev com UMA operacao trocada por uma falha de infra (o que o Keycloak real faz fora do ar)."
  [operacao]
  (let [dev (idp-dev/idp-dev)
        quebra #(throw (ex-info "keycloak fora do ar" {:tipo :infra}))]
    (reify idp/IdentityProvider
      (verificar-token [_ t] (idp/verificar-token dev t))
      (provisionar-realm! [_ e] (if (= operacao :provisionar-realm) (quebra) (idp/provisionar-realm! dev e)))
      (provisionar-realm! [_ e o] (idp/provisionar-realm! dev e o))
      (criar-usuario! [_ e u] (if (= operacao :criar-usuario) (quebra) (idp/criar-usuario! dev e u)))
      (convidar! [_ e i] (if (= operacao :convidar) (quebra) (idp/convidar! dev e i)))
      (corrigir-email-do-convite! [_ e i m] (idp/corrigir-email-do-convite! dev e i m))
      (resetar-mfa! [_ e i] (idp/resetar-mfa! dev e i))
      (apagar-realm! [_ e] (idp/apagar-realm! dev e)))))

(deftest falha-real-do-idp-continua-visivel-e-reenviar-conserta
  (doseq [operacao [:provisionar-realm :criar-usuario :convidar]]
    (let [ente (random-uuid) adm (admin! ente) alvo (identidade! "Rui Vereador")
          r (conceder (servico (idp-que-falha-em operacao)) ente adm alvo)]
      (is (= 500 (:status r)) (str "falha do IdP em " operacao " NAO e' engolida: segue 500, nunca 201"))
      (is (= #{"vereador"} (repo/papeis-de (repo-id) ente alvo))
          "o ato ja' estava gravado (banco antes do IdP): sem usuario no IdP ninguem entra, e repetir conserta")
      (is (= 201 (:status (conceder (servico (idp-dev/idp-dev)) ente adm alvo))) "o reenvio com o IdP de volta fecha o ato")
      (is (= #{"vereador"} (repo/papeis-de (repo-id) ente alvo)) "sem papel duplicado"))))

(deftest idp-dev-reenviar-convite-nao-quebra
  (let [svc (servico (idp-dev/idp-dev)) ente (random-uuid) adm (admin! ente) alvo (identidade! "Ana")]
    (conceder svc ente adm alvo)
    (is (= 200 (:status (pt/response-for svc :post (str "/identidade/acessos/" alvo "/convite")
                                         :headers {"Authorization" (bearer ente adm)})))
        "o reenvio de convite tambem fecha em dev (nao ha' e-mail a enviar)")))
