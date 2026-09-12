(ns oplenario.identidade.meu-identidade-http-test
  "GET /meu/identidade (fatia 'demo-tres-consertos' #1) — o cabecalho do FE mostrava um ator FIXO
  ('Sérgio Lopes'/'Presidente da Mesa') pra QUALQUER persona logada (achado ao vivo, ver
  paineis/mesa/page.tsx antes do conserto). Esta rota devolve o nome+papeis REAIS do ator autenticado.
  DB-free (padrao da casa): repo fake por `reify`, ator por token JSON do `idp-dev`.

  Gate `auth` APENAS, sem papel — a cidada (vinculo `cidadao`, papeis vazio) tambem precisa alcancar esta
  rota (mesmo racional de `GET /meu/notificacoes`, paineis/diplomat/http/in.clj)."
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp :as idp]
            [oplenario.rotas :as rotas]))

(def ^:private cpf-valido "52998224725")

(defn- fake-repo-identidade
  "`nome-por-id` devolve :cpf TAMBEM (deliberado, mesmo precedente de acesso_http_test.clj/fake-repo-
  identidade) — mantem `nunca-vaza-cpf` um teste de REGRESSAO de verdade: se o handler um dia trocar pra
  um metodo mais largo (ou fizer merge descuidado), a assercao 'CPF nunca aparece no corpo' continua
  tendo algo real pra pegar, em vez de virar vacuamente verdadeira so' porque o fake ficou estreito."
  [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _e _i] {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})
    (nome-por-id [_ id] {:id id :cpf cpf-valido :nome "Marina Alencar Freire"})))

(defn- ->uuid [s] (when s (java.util.UUID/fromString s)))

(defn- fake-idp []
  (reify idp/IdentityProvider
    (verificar-token [_ t]
      (try
        (let [c (json/read-value t json/keyword-keys-object-mapper)]
          (when (map? c)
            (cond-> c
              (:identidade-id c) (update :identidade-id ->uuid)
              (:ente-id c)       (update :ente-id ->uuid))))
        (catch Exception _ nil)))))

(defn- service-fn [papeis]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (fake-idp) :repo-identidade (fake-repo-identidade papeis)})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id]
  (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))

(defn- com-bearer [t] {"authorization" (str "Bearer " t)})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(deftest meu-identidade-200-com-papeis
  (let [r (pt/response-for (service-fn #{"secretario"})
                           :get "/meu/identidade"
                           :headers (com-bearer (token (random-uuid) (random-uuid))))
        corpo (ler-json r)]
    (is (= 200 (:status r)))
    (is (= "Marina Alencar Freire" (:nome corpo)))
    (is (= ["secretario"] (:papeis corpo)))))

(deftest meu-identidade-200-sem-papel-nenhum
  ;; a cidada — vinculo `cidadao`, papeis vazio. A rota NAO exige papel; sem isso ela nunca alcancaria
  ;; esta superficie e o cabecalho continuaria mentindo pra ela.
  (let [r (pt/response-for (service-fn #{})
                           :get "/meu/identidade"
                           :headers (com-bearer (token (random-uuid) (random-uuid))))
        corpo (ler-json r)]
    (is (= 200 (:status r)))
    (is (= "Marina Alencar Freire" (:nome corpo)))
    (is (= [] (:papeis corpo)))))

(deftest meu-identidade-multiplos-papeis-ordenados
  ;; presidente da Mesa: vereador + admin_ente. A ordenacao (`sort`) torna a resposta deterministica —
  ;; um set nao tem ordem estavel de iteracao entre chamadas.
  (let [r (pt/response-for (service-fn #{"vereador" "admin_ente"})
                           :get "/meu/identidade"
                           :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 200 (:status r)))
    (is (= ["admin_ente" "vereador"] (:papeis (ler-json r))))))

(deftest meu-identidade-sem-token-401
  (let [r (pt/response-for (service-fn #{"secretario"}) :get "/meu/identidade")]
    (is (= 401 (:status r)) "sem `auth`, sem sessao — fail-closed")))

(deftest meu-identidade-nunca-vaza-cpf
  (let [r (pt/response-for (service-fn #{"secretario"})
                           :get "/meu/identidade"
                           :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (not (re-find (re-pattern cpf-valido) (:body r)))
        "o fake `nome-por-id` devolve :cpf de proposito (ver docstring) — o schema `:closed true` de MeuIdentidadeOut torna estruturalmente impossivel o CPF atravessar")))

(deftest meu-identidade-le-so-do-ator-nunca-do-cliente
  ;; nao ha' path/query param nesta rota — a UNICA fonte de identidade e' o token verificado. Este teste
  ;; documenta a propriedade (a rota nem aceita um id — IDOR estruturalmente impossivel) tentando um path
  ;; com querystring solta, que a rota simplesmente ignora.
  (let [ident (random-uuid)
        r (pt/response-for (service-fn #{})
                           :get "/meu/identidade?identidade-id=00000000-0000-0000-0000-000000000099"
                           :headers (com-bearer (token (random-uuid) ident)))]
    (is (= 200 (:status r)))
    (is (= "Marina Alencar Freire" (:nome (ler-json r)))
        "o corpo vem do ator do TOKEN, o query-param forjado nunca e' lido")))
