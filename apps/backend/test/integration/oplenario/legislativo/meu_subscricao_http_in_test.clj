(ns oplenario.legislativo.meu-subscricao-http-in-test
  "Fatia 2c — a borda HTTP do requerimento COLETIVO (/meu/colegas, /meu/subscricoes, /meu/requerimentos/propostas).
  DB-free (Repo FAKE, mesmo racional de meu-requerimento-http-in-test): o banco esta' coberto em
  subscricao-requerimento-test. Foco AQUI: quem e' quem vem do login (host), so' colega com mandato vigente e'
  convidado, e os contratos 201/200/400/404/409."
  (:require [clojure.test :refer [deftest is testing]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.cadastros.components.repositorio :as repo-cadastros-comp]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.rotas :as rotas])
  (:import (java.time Instant)))

(def ^:private template "{{vereador}} requer a {{destinatario}}: {{assunto}}.")
(def ^:private modelo {:id (random-uuid) :chave "req-info" :nome "Requerimento de informação"
                       :tipo-documento "requerimento_proposicao" :corpo-template template :ativo true :lock-version 0})

;; logins -> cadastro de vereador
(def ^:private login-ana (random-uuid))
(def ^:private login-bia (random-uuid))
(def ^:private ana (random-uuid))
(def ^:private bia (random-uuid))
(def ^:private caio (random-uuid))
(def ^:private dora (random-uuid))

(def ^:private cadastros
  {login-ana {:id ana :nome "Ana Maria Prado" :nome-parlamentar "Ana Prado"}
   login-bia {:id bia :nome "Beatriz Lima" :nome-parlamentar "Bia Lima"}})

(def ^:private roster
  [{:vereador-id ana :nome "Ana Maria Prado" :nome-parlamentar "Ana Prado" :partido "PV" :estado-mandato "vigente"}
   {:vereador-id bia :nome "Beatriz Lima" :nome-parlamentar "Bia Lima" :partido "PSB" :estado-mandato "vigente"}
   {:vereador-id caio :nome "Caio Reis" :nome-parlamentar nil :partido nil :estado-mandato "vigente"}
   {:vereador-id dora :nome "Dora Mota" :nome-parlamentar "Dora" :partido "PT" :estado-mandato "licenciado"}])

(def ^:private proposta-id (random-uuid))
(def ^:private agora (Instant/parse "2026-09-26T12:00:00Z"))

(defn- proposta [& {:as over}]
  (merge {:id proposta-id :ementa "Informações" :tipo-requerimento "Requerimento de informação" :texto "T"
          :autor-nome "Ana Prado" :autor-vereador-id ana :estado "aguardando_subscricoes" :proposicao-id nil
          :criada-em agora
          :subscricoes [{:vereador-id bia :vereador-nome "Bia Lima" :estado "pendente" :respondida-em nil}
                        {:vereador-id caio :vereador-nome "Caio Reis" :estado "confirmada" :respondida-em agora}]}
         over))

(defn- fake-repo-legislativo [{:keys [criado buscar responder protocolar]
                               :or {buscar (fn [_id vid] (when (#{ana bia caio} vid) (proposta)))}}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-leg/RepoLegislativo
    (listar-modelos-ativos [_ _ente-id] [modelo])
    (buscar-modelo [_ _ente-id id] (when (= id (:id modelo)) modelo))
    (criar-proposta-requerimento! [_ _ente-id m] (some-> criado (reset! m)) {:id (:id m)})
    (buscar-proposta-requerimento [_ _ente-id id vid] (buscar id vid))
    (responder-subscricao! [_ _ente-id m] (responder m))
    (protocolar-proposta-requerimento! [_ _ente-id id autor p] (protocolar id autor p))
    (convites-de-subscricao [_ _ente-id vid]
      (when (= vid bia) [{:proposta-id proposta-id :ementa "Informações" :tipo-requerimento "Req"
                          :autor-nome "Ana Prado" :convidada-em agora}]))
    (propostas-abertas-do-autor [_ _ente-id vid]
      (when (= vid ana) [{:id proposta-id :ementa "Informações" :tipo-requerimento "Req" :criada-em agora
                          :confirmadas 1 :pendentes 1 :recusadas 0}]))))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "vereador"} :papeis papeis})))

(defn- fake-repo-cadastros []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cadastros-comp/RepoCadastros
    (vereador-por-identidade [_ _ente-id identidade-id] (get cadastros identidade-id))
    (roster-da-casa [_ _ente-id _data] roster)
    (uf-e-municipio [_ _ente-id] {:uf "CE" :municipio-nome "Fortaleza"})))

(defn- chamar [metodo url & {:keys [corpo login papeis repo] :or {login login-ana papeis #{"vereador"}}}]
  (let [svc (-> (http/servico (config/carregar)
                              (rotas/montar {:idp (idp-dev/idp-dev)
                                             :repo-identidade (fake-repo-identidade papeis)
                                             :repo-legislativo (or repo (fake-repo-legislativo {}))
                                             :repo-cadastros (fake-repo-cadastros)
                                             :registro :registro-fake
                                             :relogio (constantly agora)})
                              it/globais)
                ph/create-server ::ph/service-fn)
        r (pt/response-for svc metodo url
                           :headers {"authorization" (str "Bearer " (json/write-value-as-string
                                                                      {:sub "u" :ente-id (str (random-uuid))
                                                                       :identidade-id (str login)}))
                                     "Content-Type" "application/json"}
                           :body (some-> corpo json/write-value-as-string))]
    (assoc r :json (try (json/read-value (:body r) json/keyword-keys-object-mapper) (catch Exception _ nil)))))

(def ^:private corpo-ok
  {:modelo-id (str (:id modelo)) :ementa "Informações" :campos {"destinatario" "SEINF" "assunto" "a obra X"}
   :coautores [(str bia) (str caio)]})

;; ---------- colegas ----------

(deftest colegas-sao-os-vigentes-menos-eu
  (let [r (chamar :get "/meu/colegas")]
    (is (= 200 (:status r)))
    (is (= [{:id (str bia) :nome "Bia Lima" :partido "PSB"} {:id (str caio) :nome "Caio Reis" :partido nil}]
           (get-in r [:json :itens]))
        "sem a propria autora, sem a licenciada; nome parlamentar quando ha'")))

(deftest login-sem-cadastro-de-vereador-nao-tem-colegas
  (is (= 404 (:status (chamar :get "/meu/colegas" :login (random-uuid))))))

;; ---------- criar a proposta ----------

(deftest criar-proposta-convida-os-colegas-com-autor-do-login
  (let [criado (atom nil)
        r (chamar :post "/meu/requerimentos/propostas" :corpo corpo-ok
                  :repo (fake-repo-legislativo {:criado criado
                                                :buscar (fn [_ vid] (when (= vid ana) (proposta)))}))]
    (is (= 201 (:status r)))
    (is (true? (get-in r [:json :sou-autor])))
    (is (= ana (:autor-vereador-id @criado)) "autor = o vereador do LOGIN")
    (is (= "Ana Prado" (:autor-nome @criado)))
    (is (= [{:id bia :nome "Bia Lima"} {:id caio :nome "Caio Reis"}] (:coautores @criado)))
    (is (= "Ana Prado requer a SEINF: a obra X." (:texto @criado)) "texto congelado com o merge do servidor")
    (is (= "Requerimento de informação" (:tipo-requerimento @criado)))))

(deftest convite-so-para-colega-vigente
  (testing "licenciada, de fora, repetido, o proprio autor, ou nenhum: 400"
    (doseq [coautores [[(str dora)] [(str (random-uuid))] [(str bia) (str bia)] [(str ana)] []]]
      (is (= 400 (:status (chamar :post "/meu/requerimentos/propostas" :corpo (assoc corpo-ok :coautores coautores))))
          (str coautores)))))

(deftest so-vereador-cria-proposta
  (is (= 403 (:status (chamar :post "/meu/requerimentos/propostas" :corpo corpo-ok :papeis #{"secretario"})))))

;; ---------- ler ----------

(deftest proposta-so-para-quem-participa
  (let [r (chamar :get (str "/meu/requerimentos/propostas/" proposta-id) :login login-bia)]
    (is (= 200 (:status r)))
    (is (false? (get-in r [:json :sou-autor])))
    (is (= "pendente" (get-in r [:json :minha-subscricao])) "o convite de quem le")
    (is (= ["Bia Lima" "Caio Reis"] (map :vereador-nome (get-in r [:json :subscricoes])))))
  (is (= 404 (:status (chamar :get (str "/meu/requerimentos/propostas/" proposta-id)
                              :repo (fake-repo-legislativo {:buscar (constantly nil)}))))))

(deftest a-home-ve-convites-e-propostas-abertas
  (is (= [(str proposta-id)] (map :proposta-id (get-in (chamar :get "/meu/subscricoes" :login login-bia) [:json :itens]))))
  (is (= [{:id (str proposta-id) :ementa "Informações" :tipo-requerimento "Req" :criada-em (str agora)
           :confirmadas 1 :pendentes 1 :recusadas 0}]
         (get-in (chamar :get "/meu/requerimentos/propostas") [:json :itens]))))

;; ---------- responder ----------

(deftest confirmar-assina-com-o-login-do-coautor
  (let [visto (atom nil)
        r (chamar :post (str "/meu/requerimentos/propostas/" proposta-id "/resposta") :login login-bia
                  :corpo {:acao "confirmar"}
                  :repo (fake-repo-legislativo
                          {:responder (fn [m] (reset! visto m)
                                        {:proposta-id proposta-id :estado "confirmada" :assinatura-algoritmo "STUB-ICP-v0"})}))]
    (is (= 200 (:status r)))
    (is (= "confirmada" (get-in r [:json :estado])))
    (is (= bia (:vereador-id @visto)) "o convite respondido e' o do vereador do login")
    (is (= login-bia (:identidade-id @visto)))
    (is (= :confirmar (:acao @visto)))
    (is (some? (:assinador @visto)))))

(deftest resposta-invalida-400-sem-convite-404-conflitos-409
  (is (= 400 (:status (chamar :post (str "/meu/requerimentos/propostas/" proposta-id "/resposta")
                              :corpo {:acao "talvez"}))))
  (is (= 404 (:status (chamar :post (str "/meu/requerimentos/propostas/" proposta-id "/resposta")
                              :corpo {:acao "confirmar"} :repo (fake-repo-legislativo {:responder (constantly nil)})))))
  (doseq [[tipo motivo] [[:conflito/subscricao-respondida "subscricao-respondida"]
                         [:conflito/proposta-protocolada "proposta-protocolada"]]]
    (let [r (chamar :post (str "/meu/requerimentos/propostas/" proposta-id "/resposta") :corpo {:acao "recusar"}
                    :repo (fake-repo-legislativo {:responder (fn [_] (throw (ex-info "x" {:tipo tipo})))}))]
      (is (= 409 (:status r)))
      (is (= motivo (get-in r [:json :motivo]))))))

;; ---------- protocolar ----------

(deftest protocolar-leva-os-coautores-que-constam
  (let [visto (atom nil)
        r (chamar :post (str "/meu/requerimentos/propostas/" proposta-id "/protocolo")
                  :repo (fake-repo-legislativo
                          {:protocolar (fn [id autor p]
                                         (reset! visto {:id id :autor autor :p p})
                                         {:id (:id p) :sequencial 9 :urn-lex "urn:x" :estado "protocolada"
                                          :assinatura {:algoritmo "STUB-ICP-v0"}
                                          :coautores [{:vereador-nome "Caio Reis"}]})}))]
    (is (= 201 (:status r)))
    (is (= ["Caio Reis"] (get-in r [:json :coautores])))
    (is (= 2026 (get-in r [:json :ano])) "ano da numeracao pelo relogio do servidor")
    (is (= ana (:autor @visto)))
    (is (= "T" (get-in @visto [:p :texto])) "protocola o texto CONGELADO da proposta, nao um texto novo")
    (is (= "Ana Prado" (get-in @visto [:p :autor-texto])))))

(deftest so-o-autor-protocola
  (is (= 404 (:status (chamar :post (str "/meu/requerimentos/propostas/" proposta-id "/protocolo") :login login-bia))))
  (let [r (chamar :post (str "/meu/requerimentos/propostas/" proposta-id "/protocolo")
                  :repo (fake-repo-legislativo {:protocolar (fn [& _] (throw (ex-info "x" {:tipo :conflito/proposta-protocolada})))}))]
    (is (= 409 (:status r)))
    (is (= "proposta-protocolada" (get-in r [:json :motivo])))))
