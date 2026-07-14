(ns oplenario.cadastros.vereador-http-in-test
  "Task 5 (borda HTTP do cadastros — PRIMEIRA borda do modulo) — a vertical de leitura GET
  /cadastros/vereadores (lista) e GET /cadastros/vereadores/:id (ficha): prova a silhueta de borda
  end-to-end (controller -> repo -> adapters/out -> wire/out) + a authz grossa (papel 'secretario') + 401.
  DB-free: RepoCadastros FAKE (reify, so' os 2 metodos exercidos) + idp-dev real (mesmo precedente de
  proposicao-http-in-test/painel-http-in-test)."
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.cadastros.components.repositorio :as repo-cad]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.rotas :as rotas]))

(defn- linha-canonica [id]
  {:id id :nome "Helena Matos" :nome-parlamentar "Helena" :partido "PT"
   :estado-mandato "ativo" :cargo-mesa "presidente"})

(defn- ficha-canonica [id]
  {:vereador {:id id :nome "Helena Matos" :nome-parlamentar "Helena"}
   :mandato {:partido "PT" :estado "ativo" :natureza "titular"
             :vigencia-inicio (java.time.LocalDate/of 2025 1 1)
             :legislatura-id nil}
   :legislatura nil
   :comissoes []})

(defn- fake-repo-cadastros
  "RepoCadastros fake: `listar-vereadores` devolve `linhas`; `ficha-vereador` devolve `ficha` (ou nil, p/
  simular 404). Impl parcial proposital (so' os 2 metodos exercidos pela borda desta task)."
  [linhas ficha]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cad/RepoCadastros
    (listar-vereadores [_ _ente-id _data] linhas)
    (ficha-vereador [_ _ente-id _id _data] ficha)))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- service-fn [papeis repo-c]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-cadastros repo-c})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id]
  (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))

(defn- com-bearer [tok] {"authorization" (str "Bearer " tok)})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

;; ---------- GET /cadastros/vereadores ----------

(deftest listar-vereadores-200
  (let [ente (random-uuid) id (random-uuid)
        r (pt/response-for (service-fn #{"secretario"} (fake-repo-cadastros [(linha-canonica id)] nil))
                           :get "/cadastros/vereadores" :headers (com-bearer (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)) "GET /cadastros/vereadores com papel secretario -> 200")
    (is (= 1 (count (:vereadores body))) "o envelope {:vereadores [...]}")
    (let [v (first (:vereadores body))]
      (is (= "Helena Matos" (:nome v)))
      (is (string? (:id v)) "id como string"))))

(deftest listar-vereadores-vazio-200
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-cadastros [] nil))
                           :get "/cadastros/vereadores" :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 200 (:status r)))
    (is (= [] (:vereadores (ler-json r))))))

(deftest listar-vereadores-sem-papel-403
  (let [r (pt/response-for (service-fn #{"vereador"} (fake-repo-cadastros [] nil))
                           :get "/cadastros/vereadores" :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 403 (:status r)) "ator sem papel 'secretario' -> authz grossa nega -> 403")))

(deftest listar-vereadores-sem-token-401
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-cadastros [] nil))
                           :get "/cadastros/vereadores")]
    (is (= 401 (:status r)) "rota de modulo herda a cadeia de auth: sem token -> 401 (fail-closed)")))

;; ---------- GET /cadastros/vereadores/:id ----------

(deftest ficha-vereador-200
  (let [ente (random-uuid) id (random-uuid)
        r (pt/response-for (service-fn #{"secretario"} (fake-repo-cadastros [] (ficha-canonica id)))
                           :get (str "/cadastros/vereadores/" id)
                           :headers (com-bearer (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= "Helena Matos" (:nome body)))
    (is (= [] (:comissoes body)))))

(deftest ficha-vereador-inexistente-404
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-cadastros [] nil))
                           :get (str "/cadastros/vereadores/" (random-uuid))
                           :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 404 (:status r)))
    (is (= "vereador nao encontrado" (:erro (ler-json r))))))

(deftest ficha-vereador-id-invalido-404
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-cadastros [] nil))
                           :get "/cadastros/vereadores/nao-e-um-uuid"
                           :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 404 (:status r)) "path param nao-UUID -> 404, nunca 500")))

(deftest ficha-vereador-sem-papel-403
  (let [r (pt/response-for (service-fn #{"vereador"} (fake-repo-cadastros [] (ficha-canonica (random-uuid))))
                           :get (str "/cadastros/vereadores/" (random-uuid))
                           :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 403 (:status r)))))
