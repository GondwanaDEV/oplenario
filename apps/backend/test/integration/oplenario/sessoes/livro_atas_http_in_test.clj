(ns oplenario.sessoes.livro-atas-http-in-test
  "Onda E, `livro-atas`: as quatro rotas do livro de atas na borda. DB-free: RepoSessoes FAKE. Prova o contrato (o
  mesmo para a tela interna e o portal), que o portal responde SEM autenticacao e sem nome de servidor, que a interna
  exige autenticacao, e os 400/404 (versao malformada, ente malformado, sessao secreta, versao inexistente)."
  (:require [clojure.test :refer [deftest is testing]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.sessoes.components.repositorio :as repo-sessoes]
            [oplenario.sessoes.diplomat.http.in :as sessoes-http])
  (:import (java.time Instant)))

(def ente #uuid "10000000-0000-0000-0000-000000000001")
(def publica #uuid "30000000-0000-0000-0000-000000000003")
(def secreta #uuid "30000000-0000-0000-0000-000000000009")
(def quem #uuid "40000000-0000-0000-0000-000000000004")
(def t (Instant/parse "2026-09-14T18:00:00Z"))

(defn- sessao [id tipo publica?]
  {:id id :ente-id ente :tipo-sessao tipo :numero-sequencial 7 :estado "encerrada" :transmite-publica publica?
   :gera-ata-regimental true :aberta-em t :encerrada-em t})

(defn- versao [n] {:id (random-uuid) :versao n :origem-redacao "gerada_automaticamente" :conteudo-sha256 "sha256:ab"
                   :motivo-retificacao (when (> n 1) "nome corrigido") :rascunho-id (random-uuid)
                   :modelo-llm-id "fake:fake-1" :prompt-versao "ata-v1" :publicada-por quem :publicada-em t})

(defn- linha [id] {:sessao-id id :versao 2 :origem-redacao "redigida_externamente" :conteudo-sha256 "sha256:ab"
                   :publicada-em t :tipo-sessao "ordinaria" :numero-sequencial 7 :aberta-em t :encerrada-em t
                   :transmite-publica true :leitura-modo "presencial" :leitura-registrada-em t :leitura-ata-versao 2})

(defn- fake-repo [pedidos]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ _ id] (get {publica (sessao publica "ordinaria" true)
                                  secreta (sessao secreta "secreta" false)} id))
    (livro-de-atas [_ e so-publicas?] (swap! pedidos conj [e so-publicas?]) [(linha publica)])
    (ata-do-livro [_ _ _ v]
      (let [vs [(versao 2) (versao 1)]]
        {:ata (some-> (if v (first (filter #(= v (:versao %)) vs)) (first vs)) (assoc :texto "Aos catorze dias..."))
         :versoes vs :leitura {:modo "presencial" :registrada-em t :ata-versao 2}}))))

(defn- resolver-ente
  "O seam V1 do host (UUID coagido fail-closed), sem importar outro modulo."
  [s]
  (or (parse-uuid (str s)) (throw (ex-info "ente invalido" {:tipo :validacao/invalido}))))

(defn- fake-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ _] {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- servico [& {:keys [papeis pedidos casas] :or {papeis #{"vereador"} pedidos (atom []) casas #{ente}}}]
  (let [auth (it/autenticacao (idp-dev/idp-dev) (fake-identidade papeis))]
    (-> (http/servico (config/carregar)
                      (sessoes-http/rotas {:auth auth :repo-sessoes (fake-repo pedidos)
                                           :resolver-ente-publico resolver-ente :casa-existe? casas
                                           :roster-da-casa-em-datas (fn [& _]) :resumir-proposicoes (fn [& _])
                                           :nome-na-casa (fn [_ i] (when (= i quem) "Maria Secretária"))
                                           :relogio (tempo/relogio-fixo t)})
                      it/globais)
        ph/create-server ::ph/service-fn)))

(defn- cab [] {"authorization" (str "Bearer " (json/write-value-as-string {:sub "u" :ente-id (str ente)
                                                                            :identidade-id (str (random-uuid))}))})
(defn- ler [r] (json/read-value (:body r) json/keyword-keys-object-mapper))
(defn- GET [svc caminho & [autenticado?]] (if autenticado?
                                            (pt/response-for svc :get caminho :headers (cab))
                                            (pt/response-for svc :get caminho)))

(deftest a-lista-interna-exige-autenticacao-e-a-do-portal-nao
  (let [pedidos (atom [])
        svc (servico :pedidos pedidos)]
    (is (= 401 (:status (GET svc "/atas"))))
    (let [r (GET svc "/atas" true) b (ler r)]
      (is (= 200 (:status r)))
      (is (= [(str publica) 2 "presencial"]
             [(get-in b [:atas 0 :sessao :id]) (get-in b [:atas 0 :versao]) (get-in b [:atas 0 :leitura :modo])])))
    (let [r (GET svc (str "/portal/casa/" ente "/atas"))]
      (is (= 200 (:status r)) "o portal e' anonimo")
      (is (= 1 (count (:atas (ler r))))))
    (is (= [[ente false] [ente true]] @pedidos) "o portal pede SO' as publicas ao banco; a interna filtra por linha")
    (is (= 400 (:status (GET svc "/portal/casa/nao-e-uuid/atas"))))
    (testing "Casa inexistente: 404 como a rota-pai do portal, nunca 200 com lista vazia"
      (let [outra (random-uuid)]
        (is (= [404 404] [(:status (GET svc (str "/portal/casa/" outra "/atas")))
                          (:status (GET svc (str "/portal/casa/" outra "/atas/" publica)))]))
        (is (= "ente nao encontrado" (:erro (ler (GET svc (str "/portal/casa/" outra "/atas"))))))))))

(deftest a-ata-aberta
  (let [svc (servico)]
    (testing "interna: com o nome de quem publicou e sem a proveniencia interna da IA"
      (let [b (ler (GET svc (str "/atas/" publica) true))]
        (is (= ["Aos catorze dias..." 2 true] [(:texto b) (get-in b [:versao :versao]) (:vigente b)]))
        (is (= "Maria Secretária" (get-in b [:versao :publicada-por-nome])))
        (is (= #{:versao :origem-redacao :conteudo-sha256 :motivo-retificacao :publicada-em :publicada-por-nome}
               (set (keys (:versao b)))) "rascunho, modelo e prompt nao atravessam")))
    (testing "?versao=1 abre a anterior, marcada como nao vigente"
      (let [b (ler (GET svc (str "/atas/" publica "?versao=1") true))]
        (is (= [1 false] [(get-in b [:versao :versao]) (:vigente b)]))))
    (is (= 400 (:status (GET svc (str "/atas/" publica "?versao=zero") true))) "versao malformada nao vira a vigente")
    (is (= 404 (:status (GET svc (str "/atas/" publica "?versao=9") true))))
    (is (= 404 (:status (GET svc (str "/atas/" secreta) true))) "secreta para quem nao e' secretaria: 404, nao 403")
    (is (= 200 (:status (GET (servico :papeis #{"secretario"}) (str "/atas/" secreta) true))))
    (is (= 404 (:status (GET svc (str "/atas/" (random-uuid)) true))))
    (is (= 400 (:status (GET svc "/atas/nao-e-uuid" true))))))

(deftest a-ata-no-portal
  (let [svc (servico)]
    (let [r (GET svc (str "/portal/casa/" ente "/atas/" publica)) b (ler r)]
      (is (= 200 (:status r)))
      (is (= "Aos catorze dias..." (:texto b)))
      (is (every? nil? (map :publicada-por-nome (cons (:versao b) (:versoes b)))) "sem nome de servidor no portal"))
    (is (= 404 (:status (GET svc (str "/portal/casa/" ente "/atas/" secreta)))) "a secreta nao existe no portal")
    (is (= 404 (:status (GET (servico :papeis #{"secretario"}) (str "/portal/casa/" ente "/atas/" secreta))))
        "nem para a secretaria: o portal nao tem ator")))
