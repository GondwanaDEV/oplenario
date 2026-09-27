(ns oplenario.identidade.govbr-mint-test
  "INTEGRACAO (PG real, IdP fake): o login pelo gov.br pela borda HTTP (ADR-0015). O mint cria no 1o acesso a
  identidade, o vinculo de cidadao e o consentimento; a sessao — por cookie ou por Bearer — e' SO' de cidadao, mesmo
  quando o CPF e' de uma vereadora da Casa. O token real do Keycloak (broker de verdade) e' prova do suite :keycloak."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.identidade.db.identidade :as id]
            [oplenario.identidade.db.vinculo :as vinc]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp :as idp]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.rotas :as rotas]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- dv [ds] (let [r (mod (reduce + (map * ds (range (inc (count ds)) 1 -1))) 11)] (if (< r 2) 0 (- 11 r))))
(defn- cpf-valido [] (let [b (vec (repeatedly 9 #(rand-int 10))) d1 (dv b)] (apply str (concat b [d1 (dv (conj b d1))]))))

(defn- fake-idp
  "Tokens por nome: o teste diz quais claims VERIFICADAS cada token rende (o Keycloak real fica no suite :keycloak)."
  [tokens]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify idp/IdentityProvider
    (verificar-token [_ token] (get tokens token))))

(defn- servico [tokens]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (fake-idp tokens)
                                   :repo-identidade (assoc (repo-id/repositorio) :datasource {:ds *ds*})
                                   :info-ente (constantly {:nome-oficial "Câmara" :nome-curto "Câmara"})})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- ler [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(defn- mint! [svc token]
  (pt/response-for svc :post "/auth/sessoes" :headers {"Content-Type" "application/json"}
                   :body (json/write-value-as-string {:token token})))

(defn- eu [svc headers] (pt/response-for svc :get "/eu" :headers headers))

(defn- govbr [ente cpf] {:sub "kc" :ente-id ente :idp "govbr" :govbr-sub cpf :nome "Maria das Dores"})

(deftest primeiro-login-pelo-govbr-abre-sessao-de-cidadao
  (let [ente (random-uuid) cpf (cpf-valido)
        svc (servico {"tok" (govbr ente cpf)})
        r (mint! svc "tok")]
    (is (= 200 (:status r)) "o 1o login cria o cidadao em vez de responder 'sem vinculo ativo'")
    (let [ator (:ator (ler (eu svc {"cookie" (str "sessao=" (:sessao (ler r)))})))
          iid (:id (id/por-cpf *ds* cpf))]
      (is (= (str iid) (:identidade-id ator)))
      (is (= "cidadao" (:tipo-vinculo ator)))
      (is (= [] (:papeis ator))))))

(deftest vereadora-pelo-govbr-e-so-cidada
  (let [ente (random-uuid) cpf (cpf-valido) iid (random-uuid)]
    (id/inserir! *ds* {:id iid :cpf cpf :nome "Vereadora"})
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (vinc/criar! tx {:id (random-uuid) :ente-id ente :identidade-id iid :tipo "vereador"})
        (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id ente :identidade-id iid :papel "presidente_mesa"})))
    (let [;; o token do gov.br de alguem que TAMBEM traz identidade-id (conta vinculada) nao muda nada
          svc (servico {"tok" (assoc (govbr ente cpf) :identidade-id iid)})
          seg (:sessao (ler (mint! svc "tok")))
          pelo-cookie (:ator (ler (eu svc {"cookie" (str "sessao=" seg)})))
          pelo-bearer (:ator (ler (eu svc {"authorization" "Bearer tok"})))]
      (doseq [ator [pelo-cookie pelo-bearer]]
        (is (= (str iid) (:identidade-id ator)) "a mesma pessoa (ancora CPF)")
        (is (= "cidadao" (:tipo-vinculo ator)))
        (is (= [] (:papeis ator)) "nenhum poder institucional pelo gov.br")))))

(deftest cidadao-suspenso-nao-entra
  (let [ente (random-uuid) cpf (cpf-valido)
        svc (servico {"tok" (govbr ente cpf)})]
    (is (= 200 (:status (mint! svc "tok"))))
    (let [iid (:id (id/por-cpf *ds* cpf))]
      (tenancy/com-tenant* *ds* ente
        (fn [tx] (vinc/mudar-estado! tx (:id (first (vinc/vinculos-de tx ente iid))) "suspenso"))))
    (is (= 401 (:status (mint! svc "tok"))))))

(deftest bearer-do-govbr-sem-primeiro-login-nao-cria-nada
  (let [ente (random-uuid) cpf (cpf-valido)
        svc (servico {"tok" (govbr ente cpf)})]
    (is (= 401 (:status (eu svc {"authorization" "Bearer tok"}))))
    (is (nil? (id/por-cpf *ds* cpf)) "so' o mint cria")))
