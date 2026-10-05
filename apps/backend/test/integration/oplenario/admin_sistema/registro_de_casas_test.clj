(ns oplenario.admin-sistema.registro-de-casas-test
  "INTEGRACAO (PG real, IdP das Casas fake): o REGISTRO DE CASAS pelo console (ADR-0016, 12.1). O operador provisiona
  -> o registro emite o ente_id, o cadastros recebe o perfil, a identidade recebe o 1o administrador (admin_ente) e o
  IdP das Casas convida. A Casa fica 'provisionar' ate' o 1o administrador ENTRAR: o mint emite
  `identidade.vinculo.primeiro_acesso` e o consumidor do admin_sistema a ativa (handoff), selando a atuacao."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.admin-sistema.components.idp-admin :as idp-admin]
            [oplenario.admin-sistema.components.repositorio :as repo]
            [oplenario.admin-sistema.db.atuacao :as atuacao]
            [oplenario.admin-sistema.diplomat.consumers :as consumers]
            [oplenario.cadastros.components.repositorio :as repo-cad]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.identidade.db.identidade :as id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp :as idp]
            [oplenario.kernel.outbox :as outbox]
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

(defn- idp-casa-fake
  "Registra as chamadas; `falhar-convite` simula o Keycloak fora do ar no convite. Os tokens de Casa sao dados."
  [chamadas {:keys [falhar-convite tokens]}]
  (reify idp/IdentityProvider
    (verificar-token [_ t] (get @tokens t))
    (provisionar-realm! [_ ente] (swap! chamadas conj [:realm ente]) {:realm (str "ente-" ente)})
    (provisionar-realm! [_ ente opcoes] (swap! chamadas conj [:realm ente opcoes]) {:realm (str "ente-" ente)})
    (criar-usuario! [_ ente u] (swap! chamadas conj [:usuario ente (select-keys u [:identidade-id :email])]) {})
    (convidar! [_ ente iid]
      (when @falhar-convite (throw (ex-info "keycloak fora do ar" {})))
      (swap! chamadas conj [:convite ente iid]) true)
    (resetar-mfa! [_ _ _] nil)))

(defn- repo-op [] (assoc (repo/repositorio) :datasource {:ds *ds*}))

(defn- montar []
  (let [chamadas (atom []) falhar (atom false) tokens (atom {})]
    {:chamadas chamadas :falhar falhar :tokens tokens
     :svc (-> (http/servico (config/carregar)
                            (rotas/montar {:idp (idp-casa-fake chamadas {:falhar-convite falhar :tokens tokens})
                                           :repo-identidade (assoc (repo-id/repositorio) :datasource {:ds *ds*})
                                           :repo-cadastros (assoc (repo-cad/repositorio) :datasource {:ds *ds*})
                                           :idp-operacao (idp-admin/idp-operacao-dev)
                                           :repo-admin-sistema (repo-op)
                                           :operacao {:realm "operacao" :client-id "oplenario-console"
                                                      :sessao {:absoluta-h 8 :ociosa-min 15}}})
                            it/globais)
              ph/create-server ::ph/service-fn)}))

(defn- ler [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(defn- operador! []
  (repo/criar-operador! (repo-op) {:id (random-uuid) :email (str "op-" (random-uuid) "@oplenario.dev") :nome "Rafaela Operação"}))

(defn- como [o] {"authorization" (str "Bearer " (json/write-value-as-string {:operador-id (str (:id o))}))
                 "Content-Type" "application/json"})

(defn- corpo [cpf & [extra]]
  (merge {:nome-oficial "Câmara Municipal de Baturité" :nome-curto "Câmara de Baturité" :uf "CE"
          :municipio-ibge "2302008" :municipio-nome "Baturité"
          :admin {:nome "Renata Costa" :cpf cpf :email "Renata.Costa@camara.baturite.ce.gov.br"}}
         extra))

(defn- provisionar! [svc o cpf & [extra]]
  (pt/response-for svc :post "/operacao/casas" :headers (como o) :body (json/write-value-as-string (corpo cpf extra))))

(defn- atuacoes [svc o ente] (mapv :acao (:atuacao (ler (pt/response-for svc :get (str "/operacao/casas/" ente) :headers (como o))))))

(deftest provisionar-cria-a-casa-e-convida-o-primeiro-admin
  (let [{:keys [svc chamadas]} (montar) o (operador!) cpf (cpf-valido)
        r (provisionar! svc o (str (subs cpf 0 3) "." (subs cpf 3 6) "." (subs cpf 6 9) "-" (subs cpf 9)))
        body (ler r)
        ente (parse-uuid (get-in body [:casa :ente-id]))]
    (is (= 201 (:status r)))
    (is (= "enviado" (:convite body)))
    (is (= "provisionar" (get-in body [:casa :estado])) "a Casa espera o 1o administrador")
    (is (some? (get-in body [:casa :convite-enviado-em])))
    (testing "o cadastros tem o perfil da Casa (e o municipio, que a referencia nao tinha que ter)"
      (is (= "Câmara Municipal de Baturité" (:nome-oficial (repo-cad/buscar-ente (assoc (repo-cad/repositorio) :datasource {:ds *ds*}) ente)))))
    (testing "a identidade tem o 1o administrador, pelo CPF, com admin_ente nesta Casa"
      (let [iid (:id (id/por-cpf *ds* cpf))
            ator (repo-id/snapshot-ator (assoc (repo-id/repositorio) :datasource {:ds *ds*}) ente iid)]
        (is (contains? (set (:papeis ator)) "admin_ente"))
        (is (= [[:realm ente {:nome "Câmara Municipal de Baturité"}]
                [:usuario ente {:identidade-id iid :email "renata.costa@camara.baturite.ce.gov.br"}]
                [:convite ente iid]]
               @chamadas)
            "realm (com o nome da Casa no titulo do login, ADR-0024), usuario (e-mail em minusculas) e convite, nesta ordem")))
    (testing "a lista e a ficha do console"
      (let [lista (ler (pt/response-for svc :get "/operacao/casas" :headers (como o)))
            ficha (ler (pt/response-for svc :get (str "/operacao/casas/" ente) :headers (como o)))]
        (is (some #(= (str ente) (:ente-id %)) (:casas lista)))
        (is (pos? (get-in lista [:resumo :aguardando-admin])))
        (is (= {:nome "Renata Costa" :email "renata.costa@camara.baturite.ce.gov.br"} (:primeiro-admin ficha)))
        (is (= ["convite-enviado" "casa-provisionada"] (mapv :acao (:atuacao ficha))) "mais recente primeiro")
        (is (= "Rafaela Operação" (:operador (first (:atuacao ficha)))))
        (is (not (re-find (re-pattern cpf) (pr-str ficha))) "o CPF nao sai no console")))))

(deftest o-primeiro-acesso-do-admin-ativa-a-casa
  (let [{:keys [svc tokens]} (montar) o (operador!) cpf (cpf-valido)
        ente (parse-uuid (get-in (ler (provisionar! svc o cpf)) [:casa :ente-id]))
        iid (:id (id/por-cpf *ds* cpf))
        registro (consumers/registrar {})]
    (swap! tokens assoc "tok-renata" {:sub "kc" :ente-id ente :identidade-id iid})
    (outbox/drenar! *ds* registro)
    (is (= "provisionar" (get-in (ler (pt/response-for svc :get (str "/operacao/casas/" ente) :headers (como o))) [:casa :estado])))
    (let [mint #(pt/response-for svc :post "/auth/sessoes" :headers {"Content-Type" "application/json"}
                                 :body (json/write-value-as-string {:token "tok-renata"}))]
      (is (= 200 (:status (mint))) "a Renata entra na Casa")
      (outbox/drenar! *ds* registro)
      (let [ficha (ler (pt/response-for svc :get (str "/operacao/casas/" ente) :headers (como o)))]
        (is (= "ativo" (get-in ficha [:casa :estado])) "o handoff aconteceu")
        (is (some? (get-in ficha [:casa :ativada-em])))
        (is (= "casa-ativada" (:acao (first (:atuacao ficha)))))
        (is (nil? (:operador (first (:atuacao ficha)))) "foi a Casa que ativou, nao a Operacao"))
      (testing "o 2o login nao emite de novo (a atuacao nao cresce)"
        (is (= 200 (:status (mint))))
        (outbox/drenar! *ds* registro)
        (is (= 1 (count (filter #{"casa-ativada"} (atuacoes svc o ente))))))
      (testing "Casa ativa nao aceita reenviar o convite: agora e' com o admin da Casa"
        (is (= 409 (:status (pt/response-for svc :post (str "/operacao/casas/" ente "/convite") :headers (como o))))))))
  (is (true? (:integra? (atuacao/verificar-corrente *ds*))) "a corrente segue integra"))

(deftest keycloak-fora-do-ar-deixa-a-casa-registrada-e-o-convite-se-retoma
  (let [{:keys [svc chamadas falhar]} (montar) o (operador!) cpf (cpf-valido)]
    (reset! falhar true)
    (let [r (provisionar! svc o cpf) b (ler r) ente (get-in b [:casa :ente-id])]
      (is (= 201 (:status r)))
      (is (= "falhou" (:convite b)) "a tela diz que o convite nao saiu")
      (is (nil? (get-in b [:casa :convite-enviado-em])))
      (reset! falhar false)
      (reset! chamadas [])
      (let [r2 (pt/response-for svc :post (str "/operacao/casas/" ente "/convite") :headers (como o))]
        (is (= 200 (:status r2)))
        (is (some? (:convite-enviado-em (ler r2))))
        (is (= :convite (first (last @chamadas))))
        (is (= "convite-reenviado" (first (atuacoes svc o ente))))))))

(deftest reprovisionar-o-realm-fica-na-atuacao
  (let [{:keys [svc chamadas]} (montar) o (operador!)
        ente (get-in (ler (provisionar! svc o (cpf-valido))) [:casa :ente-id])]
    (reset! chamadas [])
    (is (= 200 (:status (pt/response-for svc :post (str "/operacao/casas/" ente "/realm") :headers (como o)))))
    (is (= [[:realm (parse-uuid ente) {:nome "Câmara Municipal de Baturité"}]] @chamadas)
        "reprovisionar converge tambem o nome da Casa no realm (ADR-0024)")
    (is (= "realm-reprovisionado" (first (atuacoes svc o ente))))))

(deftest entrada-invalida-e-casa-inexistente
  (let [{:keys [svc]} (montar) o (operador!)]
    (is (= 400 (:status (provisionar! svc o "11111111111"))) "CPF com digito errado")
    (is (= 400 (:status (provisionar! svc o (cpf-valido) {:uf "ceara"}))))
    (is (= 400 (:status (provisionar! svc o (cpf-valido) {:municipio-ibge "123"}))))
    (is (= 400 (:status (provisionar! svc o (cpf-valido) {:ente-id (str (random-uuid))}))) "o ente_id nunca vem do corpo")
    (is (= 404 (:status (pt/response-for svc :get (str "/operacao/casas/" (random-uuid)) :headers (como o)))))
    (is (= 404 (:status (pt/response-for svc :get "/operacao/casas/nao-e-uuid" :headers (como o)))))
    (is (= 404 (:status (pt/response-for svc :post (str "/operacao/casas/" (random-uuid) "/convite") :headers (como o)))))))

(deftest so-o-operador-provisiona
  (let [{:keys [svc tokens]} (montar) ente (random-uuid) cpf (cpf-valido)
        iid (id/inserir! *ds* {:id (random-uuid) :cpf cpf :nome "Admin da Casa"})]
    (repo-id/conceder-acesso! (assoc (repo-id/repositorio) :datasource {:ds *ds*}) ente
                              {:id (random-uuid) :ente-id ente :identidade-id iid :tipo "servidor" :estado "ativo"}
                              ["admin_ente"])
    (swap! tokens assoc "tok-admin" {:sub "kc" :ente-id ente :identidade-id iid})
    (is (= 401 (:status (pt/response-for svc :post "/operacao/casas"
                                         :headers {"authorization" "Bearer tok-admin" "Content-Type" "application/json"}
                                         :body (json/write-value-as-string (corpo (cpf-valido))))))
        "o admin_ente de uma Casa nao cria Casa")
    (is (= 401 (:status (pt/response-for svc :get "/operacao/casas" :headers {"authorization" "Bearer tok-admin"}))))))
