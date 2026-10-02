(ns oplenario.cadastros.setores-http-test
  "INTEGRACAO (PG real + borda HTTP): ADR-0020 Eixo 1 — os SETORES da Casa em /administracao. So' o `admin_ente`;
  nome unico por Casa sem diferenca de caixa; renomear e desativar; a lotacao troca inteira e so' aceita pessoas com
  vinculo ativo na Casa (o host diz quem sao); outra Casa nao ve nada; setor nao se apaga (sem GRANT de DELETE)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.components.repositorio :as repo]
            [oplenario.cadastros.diplomat.http.in :as cadastros-http]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *c* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*c* c] (try (t) (finally (component/stop c)))))))

(def admin (random-uuid))
(def secretaria (random-uuid))
(def ana (random-uuid))
(def bruno (random-uuid))

(defn- fake-identidade []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ iid]
      (cond (= iid admin) {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis #{"admin_ente"}}
            (= iid secretaria) {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis #{"secretario"}}))))

(def ^:private pessoas {ana "Ana Lima" bruno "Bruno Sales" admin "Admin"})

(defn- servico []
  (-> (http/servico (config/carregar)
                    (cadastros-http/rotas-de-setores {:auth (it/autenticacao (idp-dev/idp-dev) (fake-identidade))
                                                      :repo-cadastros (repo/->RepoCadastrosPg *c*)
                                                      :pessoas-da-casa (constantly pessoas)})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- pedir [svc metodo caminho ente quem & [corpo]]
  (let [r (pt/response-for svc metodo caminho
                           :headers (cond-> {"Authorization" (str "Bearer " (json/write-value-as-string
                                                                             {:sub "u" :ente-id (str ente)
                                                                              :identidade-id (str quem)}))}
                                      corpo (assoc "Content-Type" "application/json"))
                           :body (when corpo (json/write-value-as-string corpo)))]
    {:status (:status r) :corpo (when (seq (:body r)) (json/read-value (:body r) json/keyword-keys-object-mapper))}))

(deftest setores-de-ponta-a-ponta
  (let [ente (random-uuid) svc (servico)
        r (pedir svc :post "/administracao/setores" ente admin {:nome "  Jurídico "})
        sid (get-in r [:corpo :id])]
    (testing "criar: nome aparado, ativo, sem membros"
      (is (= 201 (:status r)))
      (is (= {:nome "Jurídico" :ativo true :membros []} (select-keys (:corpo r) [:nome :ativo :membros]))))
    (testing "nome unico por Casa, sem diferenca de caixa"
      (is (= 409 (:status (pedir svc :post "/administracao/setores" ente admin {:nome "JURÍDICO"}))))
      (is (= 201 (:status (pedir svc :post "/administracao/setores" (random-uuid) admin {:nome "Jurídico"})))
          "outra Casa pode ter o mesmo nome"))
    (testing "lotacao: troca inteira, so' pessoas ativas da Casa"
      (let [r (pedir svc :put (str "/administracao/setores/" sid "/membros") ente admin
                     {:identidades [(str ana) (str bruno) (str ana)]})]
        (is (= 200 (:status r)))
        (is (= [{:identidade-id (str ana) :nome "Ana Lima" :ativo true}
                {:identidade-id (str bruno) :nome "Bruno Sales" :ativo true}]
               (get-in r [:corpo :membros]))))
      (let [estranha (random-uuid)
            r (pedir svc :put (str "/administracao/setores/" sid "/membros") ente admin
                     {:identidades [(str ana) (str estranha)]})]
        (is (= 422 (:status r)) "quem nao e' pessoa ativa da Casa recusa o pedido inteiro")
        (is (= [(str estranha)] (get-in r [:corpo :identidades]))))
      (let [r (pedir svc :put (str "/administracao/setores/" sid "/membros") ente admin {:identidades [(str bruno)]})]
        (is (= [(str bruno)] (mapv :identidade-id (get-in r [:corpo :membros]))) "Ana saiu, Bruno ficou")))
    (testing "renomear e desativar"
      (let [r (pedir svc :put (str "/administracao/setores/" sid) ente admin {:nome "Procuradoria" :ativo false})]
        (is (= 200 (:status r)))
        (is (= {:nome "Procuradoria" :ativo false} (select-keys (:corpo r) [:nome :ativo])))
        (is (= 1 (count (get-in r [:corpo :membros]))) "a lotacao fica"))
      (is (= 400 (:status (pedir svc :put (str "/administracao/setores/" sid) ente admin {})))
          "sem nome nem ativo e' pedido vazio"))
    (testing "listar: os ativos e os inativos"
      (pedir svc :post "/administracao/setores" ente admin {:nome "Protocolo"})
      (let [ss (get-in (pedir svc :get "/administracao/setores" ente admin) [:corpo :setores])]
        (is (= ["Procuradoria" "Protocolo"] (mapv :nome ss)))
        (is (= [false true] (mapv :ativo ss)))))
    (testing "so' o admin_ente: a secretaria leva 403"
      (is (= 403 (:status (pedir svc :get "/administracao/setores" ente secretaria))))
      (is (= 403 (:status (pedir svc :post "/administracao/setores" ente secretaria {:nome "X"})))))
    (testing "outra Casa nao ve nem mexe"
      (let [outra (random-uuid)]
        (is (empty? (get-in (pedir svc :get "/administracao/setores" outra admin) [:corpo :setores])))
        (is (= 404 (:status (pedir svc :put (str "/administracao/setores/" sid) outra admin {:nome "Y"}))))
        (is (= 404 (:status (pedir svc :put (str "/administracao/setores/" sid "/membros") outra admin
                                   {:identidades []}))))))
    (testing "o setor nao se apaga: o role da aplicacao nao tem DELETE"
      (is (thrown? Exception
                   (tenancy/com-tenant* (:ds *c*) ente
                     #(jdbc/execute! % ["DELETE FROM cadastros.setor WHERE ente_id = ?" ente])))))))

(deftest membro-que-perdeu-o-acesso-aparece-inativo
  (let [ente (random-uuid) svc (servico)
        sid (get-in (pedir svc :post "/administracao/setores" ente admin {:nome "Secretaria"}) [:corpo :id])
        sumido (random-uuid)]
    ;; lotado quando era ativo (direto no banco, como se o vinculo tivesse sido suspenso depois)
    (tenancy/com-tenant* (:ds *c*) ente
      #(jdbc/execute! % ["INSERT INTO cadastros.setor_membro (ente_id, setor_id, identidade_id) VALUES (?, ?, ?)"
                         ente (parse-uuid sid) sumido]))
    (let [[s] (get-in (pedir svc :get "/administracao/setores" ente admin) [:corpo :setores])]
      (is (= [{:identidade-id (str sumido) :nome nil :ativo false}] (:membros s))))))
