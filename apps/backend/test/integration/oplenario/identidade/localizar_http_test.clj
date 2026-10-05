(ns oplenario.identidade.localizar-http-test
  "ADR-0024 — POST /auth/localizar: a entrada pelo CPF. Rota PUBLICA (pre-login). O CPF chega no CORPO (nunca na URL:
  URL vai para log de acesso); a resposta diz em quais Casas a pessoa tem acesso institucional e o `login-hint` (o
  identidade-id, o usuario dela no Keycloak de cada Casa). DB-free: repo e seams FAKE via `montar`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.rotas :as rotas]))

(def ^:private cpf "52998224725")   ; CPF valido (digitos verificadores conferem)

(defn- repo-fake
  "identidade-por-cpf: {cpf -> id}; casas: {id -> [ente-id]}. Registra os CPFs consultados."
  [cpfs casas consultas]
  (reify repo-id/RepoIdentidade
    (id-por-cpf [_ c] (swap! consultas conj c) (get cpfs c))
    (casas-com-acesso-institucional [_ iid] (get casas iid []))))

(defn- servico [{:keys [repo casa-para-login entrada]}]
  (-> (http/servico (config/carregar)
                    (rotas/montar (cond-> {:idp (idp-dev/idp-dev) :repo-identidade repo
                                           :casa-para-login (or casa-para-login
                                                                (fn [id] {:nome-oficial (str "Câmara " id)
                                                                          :nome-curto "Câmara"}))}
                                    entrada (assoc :entrada entrada)))
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- localizar [svc corpo & {:keys [ip]}]
  (pt/response-for svc :post "/auth/localizar"
                   :headers (cond-> {"Content-Type" "application/json"} ip (assoc "X-Forwarded-For" ip))
                   :body (json/write-value-as-string corpo)))

(defn- ler [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(deftest uma-casa-devolve-a-casa-e-o-login-hint
  (let [iid (random-uuid) casa (random-uuid) consultas (atom [])
        r (localizar (servico {:repo (repo-fake {cpf iid} {iid [casa]} consultas)}) {:cpf "529.982.247-25"})]
    (is (= 200 (:status r)))
    (is (= {:casas [{:ente-id (str casa) :nome-oficial (str "Câmara " casa) :nome-curto "Câmara"}]
            :login-hint (str iid)}
           (ler r)))
    (is (= [cpf] @consultas) "a mascara do CPF (pontos e traco) sai antes da consulta")))

(deftest varias-casas-em-ordem-de-nome
  (let [iid (random-uuid) [a b] [(random-uuid) (random-uuid)]
        nomes {a "Câmara Municipal de Russas" b "Câmara Municipal de Baturité"}
        r (localizar (servico {:repo (repo-fake {cpf iid} {iid [a b]} (atom []))
                               :casa-para-login (fn [id] {:nome-oficial (nomes id) :nome-curto "x"})})
                     {:cpf cpf})]
    (is (= ["Câmara Municipal de Baturité" "Câmara Municipal de Russas"] (map :nome-oficial (:casas (ler r)))))))

(deftest casa-encerrada-ou-inexistente-fica-fora
  (let [iid (random-uuid) [viva encerrada] [(random-uuid) (random-uuid)]
        r (localizar (servico {:repo (repo-fake {cpf iid} {iid [viva encerrada]} (atom []))
                               :casa-para-login (fn [id] (when (= id viva) {:nome-oficial "Viva" :nome-curto "Viva"}))})
                     {:cpf cpf})]
    (is (= [(str viva)] (map :ente-id (:casas (ler r)))))))

(deftest cpf-sem-acesso-devolve-lista-vazia-sem-hint
  (testing "CPF sem identidade"
    (let [r (localizar (servico {:repo (repo-fake {} {} (atom []))}) {:cpf cpf})]
      (is (= 200 (:status r)))
      (is (= {:casas []} (ler r)))))
  (testing "identidade sem Casa (ou so' Casas encerradas): tambem sem hint — nao entrega o id de ninguem"
    (let [iid (random-uuid)
          r (localizar (servico {:repo (repo-fake {cpf iid} {iid [(random-uuid)]} (atom []))
                                 :casa-para-login (constantly nil)})
                       {:cpf cpf})]
      (is (= {:casas []} (ler r))))))

(deftest cpf-invalido-e-400-sem-consultar
  (let [consultas (atom [])
        svc (servico {:repo (repo-fake {} {} consultas)})]
    (doseq [corpo [{:cpf "52998224724"} {:cpf "111.111.111-11"} {:cpf "123"} {:cpf 52998224725} {} {:cpf (str/join (repeat 40 "1"))}]]
      (is (= 400 (:status (localizar svc corpo))) (str "corpo " corpo)))
    (is (= 400 (:status (pt/response-for svc :post "/auth/localizar" :headers {"Content-Type" "application/json"}
                                         :body "[1,2]"))))
    (is (empty? @consultas) "nada chega ao banco com CPF que nao passa no digito verificador")))

(deftest o-cpf-nunca-vai-na-url
  (is (= 404 (:status (pt/response-for (servico {:repo (repo-fake {} {} (atom []))}) :get (str "/auth/localizar?cpf=" cpf))))
      "nao existe GET: CPF em query string acabaria no log de acesso"))

(deftest limite-por-ip
  (let [svc (servico {:repo (repo-fake {} {} (atom [])) :entrada {:limite-por-ip 2 :janela-min 5}})]
    (is (= 200 (:status (localizar svc {:cpf cpf} :ip "203.0.113.7"))))
    (is (= 200 (:status (localizar svc {:cpf cpf} :ip "203.0.113.7"))))
    (let [r (localizar svc {:cpf cpf} :ip "203.0.113.7")]
      (is (= 429 (:status r)) "a 3a do mesmo IP na janela e' recusada")
      (is (re-matches #"\d+" (get-in r [:headers "Retry-After"])) "diz quantos segundos esperar"))
    (is (= 200 (:status (localizar svc {:cpf cpf} :ip "198.51.100.9"))) "outro IP segue entrando")))
