(ns oplenario.agente-http-test
  "INTEGRACAO (PG real + borda HTTP): B.3 — a tela pergunta ao assistente pelo core. A credencial delegada nasce para
  a execucao, vai ao satelite (falso aqui) e morre ao fim, com resposta ou com a IA fora. A conversa volta em SSE."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.agente :as agente]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.autenticacao :as auten]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.identidade.db.identidade :as id]
            [oplenario.identidade.db.vinculo :as vinc]
            [oplenario.integracao-ia.components.repositorio :as repo-ia]
            [oplenario.integracao-ia.diplomat.http.out :as plataforma-ia]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- repo [] (assoc (repo-id/repositorio) :datasource {:ds *ds*}))

(defn- dv [ds] (let [r (mod (reduce + (map * ds (range (inc (count ds)) 1 -1))) 11)] (if (< r 2) 0 (- 11 r))))
(defn- cpf-valido [] (let [b (vec (repeatedly 9 #(rand-int 10))) d1 (dv b)] (apply str (concat b [d1 (dv (conj b d1))]))))

(defn- pessoa! [ente & papeis]
  (let [iid (random-uuid)]
    (id/inserir! *ds* {:id iid :cpf (cpf-valido) :nome "Pessoa"})
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (vinc/criar! tx {:id (random-uuid) :ente-id ente :identidade-id iid :tipo "servidor"})
        (doseq [p papeis] (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id ente :identidade-id iid :papel p}))))
    iid))

(def ^:private resposta-ia
  {:passos [{:ferramenta "situacao_da_materia" :argumentos {:tipo "projeto_lei" :sequencial 12 :ano 2026} :ok true
             :enviado-ao-modelo true}]
   :resposta {:execucao-id "e1" :texto "Segundo o sistema da Casa, ementa: Merenda. [[ferramenta:situacao_da_materia#1 | ementa: Merenda]]"
              :citacoes [{:fonte-id "ferramenta:situacao_da_materia#1" :trecho "ementa: Merenda" :status "conferida"}]
              :paragrafos-sem-fonte [] :incerteza "normal" :modelo "fake-1" :contaminado false}
   :indisponivel nil})

(defn- ia [pedidos & {:keys [fora?]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify plataforma-ia/PlataformaIA
    (executar-agente [_ ente-id pedido]
      (swap! pedidos conj [ente-id pedido])
      (if fora? (throw (ex-info "fora" {:tipo :ia/indisponivel :motivo "teste"})) resposta-ia))))

(defn- servico [plataforma & {:keys [repo-integracao-ia]}]
  (let [auth (it/autenticacao (idp-dev/idp-dev) (repo))]
    (-> (http/servico (config/carregar) (agente/rotas {:auth auth :repo-identidade (repo) :ia plataforma
                                                       :repo-integracao-ia repo-integracao-ia})
                      it/globais)
        ph/create-server ::ph/service-fn)))

(defn- perguntar [svc ente iid corpo]
  (pt/response-for svc :post "/agente/perguntas"
                   :headers {"Content-Type" "application/json"
                             "Authorization" (str "Bearer " (json/write-value-as-string
                                                             {:sub "u" :ente-id (str ente) :identidade-id (str iid)}))}
                   :body (json/write-value-as-string corpo)))

(defn- eventos [body]
  (for [bloco (str/split (str/trim body) #"\n\n")
        :let [[_ nome] (re-find #"event: (\S+)" bloco)
              [_ dado] (re-find #"data: (.*)" bloco)]]
    [nome (json/read-value dado)]))

(deftest a-conversa-volta-em-sse-e-a-credencial-morre-ao-fim
  (let [ente (random-uuid)
        iid (pessoa! ente "secretario")
        pedidos (atom [])
        r (perguntar (servico (ia pedidos)) ente iid {:pergunta "Qual a situacao do PL 12/2026?"})
        evs (eventos (:body r))]
    (is (= 200 (:status r)))
    (is (str/starts-with? (get-in r [:headers "Content-Type"]) "text/event-stream"))
    (is (= ["passo" "resposta" "fim"] (map first evs)))
    (is (= "situacao_da_materia" (get-in (first evs) [1 "ferramenta"])))
    (is (= "conferida" (get-in (second evs) [1 "citacoes" 0 "status"])))
    (let [[ente-ia {:keys [credencial correlation_id pergunta]}] (first @pedidos)]
      (is (= ente ente-ia) "a Casa vem do ator, nunca do corpo")
      (is (= "Qual a situacao do PL 12/2026?" pergunta))
      (is (= correlation_id (get-in (last evs) [1 "execucao-id"])))
      (is (nil? (auten/resolver-agente (repo) credencial)) "a credencial foi revogada ao fim da execucao"))))

(deftest ia-fora-e-siga-pela-tela-e-a-credencial-morre-igual
  (let [ente (random-uuid)
        iid (pessoa! ente "vereador")
        pedidos (atom [])
        evs (eventos (:body (perguntar (servico (ia pedidos :fora? true)) ente iid {:pergunta "pauta de amanha?"})))]
    (is (= ["indisponivel" "fim"] (map first evs)))
    (is (re-find #"Siga pela tela" (get-in (first evs) [1 "mensagem"])))
    (is (nil? (auten/resolver-agente (repo) (:credencial (second (first @pedidos))))))))

(deftest publico-sai-do-papel
  (let [ente (random-uuid)
        pedidos (atom [])
        svc (servico (ia pedidos))]
    (testing "vereador nao pede o conjunto da secretaria"
      (is (= 403 (:status (perguntar svc ente (pessoa! ente "vereador") {:pergunta "oi?" :publico "secretaria"})))))
    (testing "quem nao e' secretaria nem vereador nao pergunta"
      (is (= 403 (:status (perguntar svc ente (pessoa! ente "admin_ente") {:pergunta "oi?"})))))
    (testing "quem tem os dois escolhe; sem escolha, a secretaria"
      (let [iid (pessoa! ente "secretario" "vereador")]
        (perguntar svc ente iid {:pergunta "oi?"})
        (perguntar svc ente iid {:pergunta "oi?" :publico "vereador"})
        (is (= 2 (count @pedidos)))))
    (testing "pergunta vazia e' 400"
      (is (= 400 (:status (perguntar svc ente (pessoa! ente "secretario") {:pergunta " "})))))))

(deftest proposta-de-ato-da-execucao-vai-a-tela
  ;; B.6 / ADR-0012: a execucao recebe `ato` (que por agente so' propoe); a proposta criada nela vira evento `proposta`
  ;; para a tela levar a pessoa a confirmar. Aqui o satelite falso faz o papel do MCP e grava a proposta.
  (let [ente (random-uuid)
        iid (pessoa! ente "vereador")
        ri (repo-ia/map->RepoIntegracaoIAPg {:datasource {:ds *ds*}})
        classes (atom nil)
        plataforma #_{:clj-kondo/ignore [:missing-protocol-method]}
        (reify plataforma-ia/PlataformaIA
          (executar-agente [_ ente-id {:keys [credencial correlation_id]}]
            (reset! classes (get-in (auten/resolver-agente (repo) credencial) [:via :classes]))
            (repo-ia/criar-proposta! ri {:ente-id ente-id :execucao-id (parse-uuid correlation_id) :identidade-id iid
                                         :agente "assistente-da-casa" :ferramenta "protocolar_requerimento"
                                         :entrada {} :titulo "Protocolar o requerimento “X”" :texto "texto"
                                         :ritual "assinatura" :contaminada-por []
                                         :expira-em (java.sql.Timestamp/from (.plusSeconds (java.time.Instant/now) 3600))})
            resposta-ia))
        evs (eventos (:body (perguntar (servico plataforma :repo-integracao-ia ri) ente iid {:pergunta "protocole"})))]
    (is (= #{:leitura :ato} @classes) "ato concedido: por agente, so' proposta")
    (is (= ["passo" "proposta" "resposta" "fim"] (map first evs)))
    (is (= {"titulo" "Protocolar o requerimento “X”" "ritual" "assinatura"}
           (select-keys (second (second evs)) ["titulo" "ritual"])))))
