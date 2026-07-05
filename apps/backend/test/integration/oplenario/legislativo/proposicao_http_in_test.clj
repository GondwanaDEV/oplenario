(ns oplenario.legislativo.proposicao-http-in-test
  "Onda B Slice 1 (borda HTTP do legislativo) — a vertical de leitura GET /legislativo/proposicoes: prova a
  silhueta de borda end-to-end (adapters/in -> controller -> repo -> adapters/out -> wire/out) + a authz
  grossa (papel 'secretario') + 401. DB-free: RepoLegislativo FAKE (reify, so' os 2 metodos exercidos) +
  idp-dev real (mesmo precedente de tramitacao-http-in-test/votacao-http-in-test)."
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.rotas :as rotas]))

(defn- item-canonico [ente]
  {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :sequencial 42
   :urn-lex "urn:lex:br:camara.municipal.fortaleza:projeto.lei:2026;42"
   :ementa "Cria o Programa Municipal de Hortas Comunitarias"
   :autor-tipo "vereador" :autor-texto "Helena Matos" :estado "em_comissoes"
   :atualizado-em (java.time.Instant/parse "2026-05-21T10:00:00Z")})

(defn- fake-repo-legislativo
  "RepoLegislativo fake: `listar-e-contar-proposicoes` devolve {:itens :total} (review ecc — o metodo unico
  composto que o controller chama, Onda B Slice 1). Impl parcial proposital (so' o metodo exercido).
  `filtros-recebidos` (atom) captura o filtro que o controller repassou ao Repo — prova que a coercao da
  borda chegou intacta."
  [itens total filtros-recebidos]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-leg/RepoLegislativo
    (listar-e-contar-proposicoes [_ _ente-id filtro]
      (reset! filtros-recebidos filtro)
      {:itens itens :total total})))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- service-fn [papeis repo-l]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-legislativo repo-l})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id]
  (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))

(defn- com-bearer [tok] {"authorization" (str "Bearer " tok)})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(deftest listar-proposicoes-200
  (let [ente (random-uuid)
        r (pt/response-for (service-fn #{"secretario"} (fake-repo-legislativo [(item-canonico ente)] 1284 (atom nil)))
                           :get "/legislativo/proposicoes" :headers (com-bearer (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= 1284 (:total body))) (is (= 1 (:pagina body))) (is (= 20 (:tamanho-pagina body)))
    (let [i (first (:itens body))]
      (is (= "projeto_lei" (:tipo i)))
      (is (string? (:id i)) "id como string")
      (is (not (contains? i :ente-id)) "ente-id (tenant) nao vaza"))))

(deftest listar-proposicoes-vazio-200
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-legislativo [] 0 (atom nil)))
                           :get "/legislativo/proposicoes" :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 200 (:status r)))
    (is (= [] (:itens (ler-json r))))))

(deftest listar-proposicoes-sem-papel-403
  (let [r (pt/response-for (service-fn #{"vereador"} (fake-repo-legislativo [] 0 (atom nil)))
                           :get "/legislativo/proposicoes" :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 403 (:status r)) "ator sem papel 'secretario' -> authz grossa nega -> 403")))

(deftest listar-proposicoes-sem-token-401
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-legislativo [] 0 (atom nil)))
                           :get "/legislativo/proposicoes")]
    (is (= 401 (:status r)) "rota de modulo herda a cadeia de auth: sem token -> 401 (fail-closed)")))

(deftest listar-proposicoes-tamanho-invalido-400
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-legislativo [] 0 (atom nil)))
                           :get "/legislativo/proposicoes?tamanho=999"
                           :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 400 (:status r)) "tamanho fora da faixa -> 400 na borda, nunca 500")))

(deftest listar-proposicoes-ordenar-por-invalido-400
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-legislativo [] 0 (atom nil)))
                           :get "/legislativo/proposicoes?ordenar-por=senha"
                           :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 400 (:status r)) "ordenar-por fora do allowlist -> 400, nunca interpola no SQL")))

(deftest listar-proposicoes-repassa-filtro-da-query-ao-repo
  (let [ente (random-uuid) filtros (atom nil)
        r (pt/response-for (service-fn #{"secretario"} (fake-repo-legislativo [] 0 filtros))
                           :get "/legislativo/proposicoes?tipo=projeto_lei&ano=2026"
                           :headers (com-bearer (token ente (random-uuid))))]
    (is (= 200 (:status r)))
    (is (= "projeto_lei" (:tipo @filtros)))
    (is (= 2026 (:ano @filtros)))))
