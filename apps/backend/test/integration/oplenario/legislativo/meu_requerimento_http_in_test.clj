(ns oplenario.legislativo.meu-requerimento-http-in-test
  "Fatia 2a — a borda HTTP /meu/modelos-requerimento, /meu/requerimentos/previa e /meu/requerimentos. DB-free
  (Repo FAKE, mesmo racional de meu-parecer-http-in-test): o banco esta' coberto em requerimento-vereador-test.
  Foco AQUI: gate de papel 'vereador', o host resolvendo AUTOR e municipio (nunca o corpo), o relogio do
  servidor na numeracao e na data, e os contratos 201/404/400."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
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

(def ^:private template "{{vereador}} requer a {{destinatario}}: {{assunto}}. Fortaleza, {{data}}.")
(def ^:private modelo-req {:id (random-uuid) :chave "req-info" :nome "Requerimento de informação"
                           :tipo-documento "requerimento_proposicao" :corpo-template template :ativo true
                           :lock-version 0})
(def ^:private modelo-oficio {:id (random-uuid) :chave "oficio" :nome "Ofício"
                              :tipo-documento "oficio" :corpo-template "Ao {{x}}" :ativo true :lock-version 0})

(defn- fake-repo-legislativo [protocolado]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-leg/RepoLegislativo
    (listar-modelos-ativos [_ _ente-id] [modelo-oficio modelo-req])
    (buscar-modelo [_ _ente-id id] (first (filter #(= id (:id %)) [modelo-req modelo-oficio])))
    (protocolar! [_ _ente-id p]
      (reset! protocolado p)
      {:id (:id p) :sequencial 7 :urn-lex "urn:lex:br;ceara;fortaleza:camara.municipal:requerimento:2026;7"
       :estado "protocolada" :assinatura {:algoritmo "STUB-ICP-v0" :assinatura-b64 "x"}})))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "vereador"} :papeis papeis})))

(def ^:private vereador-id (random-uuid))

(defn- fake-repo-cadastros [tem-cadastro?]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cadastros-comp/RepoCadastros
    (vereador-por-identidade [_ _ente-id _identidade-id]
      (when tem-cadastro? {:id vereador-id :nome "Ana Maria Prado" :nome-parlamentar "Ana Prado"}))
    (uf-e-municipio [_ _ente-id] {:uf "CE" :municipio-nome "Fortaleza"})))

(defn- service-fn [& {:keys [papeis tem-cadastro? protocolado]
                      :or {papeis #{"vereador"} tem-cadastro? true protocolado (atom nil)}}]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-legislativo (fake-repo-legislativo protocolado)
                                   :repo-cadastros (fake-repo-cadastros tem-cadastro?)
                                   :registro :registro-fake
                                   :relogio (constantly (Instant/parse "2026-09-26T12:00:00Z"))})
                    it/globais)
      ph/create-server ::ph/service-fn))

(def ^:private identidade (random-uuid))
(defn- headers [] {"authorization" (str "Bearer " (json/write-value-as-string {:sub "u" :ente-id (str (random-uuid))
                                                                              :identidade-id (str identidade)}))
                   "Content-Type" "application/json"})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))
(defn- corpo [m] (json/write-value-as-string m))

(def ^:private campos {"destinatario" "Secretaria de Obras" "assunto" "a obra X"})

(deftest modelos-so-do-tipo-requerimento-com-campos
  (let [r (pt/response-for (service-fn) :get "/meu/modelos-requerimento" :headers (headers))]
    (is (= 200 (:status r)))
    (is (= {:itens [{:id (str (:id modelo-req)) :nome "Requerimento de informação" :campos ["destinatario" "assunto"]}]}
           (ler-json r)))))

(deftest rotas-exigem-papel-vereador
  (let [svc (service-fn :papeis #{"secretario"})]
    (is (= 403 (:status (pt/response-for svc :get "/meu/modelos-requerimento" :headers (headers)))))
    (is (= 403 (:status (pt/response-for svc :post "/meu/requerimentos" :headers (headers)
                                         :body (corpo {:modelo-id (str (:id modelo-req)) :campos campos :ementa "E"})))))))

(deftest previa-com-nome-do-login-e-data-do-servidor
  (let [r (pt/response-for (service-fn) :post "/meu/requerimentos/previa" :headers (headers)
                           :body (corpo {:modelo-id (str (:id modelo-req)) :campos campos}))]
    (is (= 200 (:status r)))
    (is (= "Ana Prado requer a Secretaria de Obras: a obra X. Fortaleza, 26 de setembro de 2026."
           (:texto (ler-json r))))))

(deftest protocolar-201-autor-do-login-e-assinado
  (let [protocolado (atom nil)
        r (pt/response-for (service-fn :protocolado protocolado) :post "/meu/requerimentos" :headers (headers)
                           :body (corpo {:modelo-id (str (:id modelo-req)) :ementa "Informações sobre a obra X"
                                         :campos (assoc campos "vereador" "Nome Forjado")}))
        body (ler-json r)
        p @protocolado]
    (is (= 201 (:status r)))
    (is (= {:ano 2026 :sequencial 7 :estado "protocolada" :assinatura-algoritmo "STUB-ICP-v0"}
           (select-keys body [:ano :sequencial :estado :assinatura-algoritmo])))
    (is (= (str (:id p)) (:proposicao-id body)))
    (is (= "requerimento" (:tipo p)))
    (is (= 2026 (:ano p)) "o ano da numeracao vem do relogio do servidor")
    (is (= ["vereador" vereador-id "Ana Prado"] [(:autor-tipo p) (:autor-id p) (:autor-texto p)])
        "autoria resolvida do login (nome parlamentar), nunca do corpo")
    (is (= identidade (:created-by p)))
    (is (= identidade (:assinado-por p)))
    (is (some? (:assinador p)) "o handler construiu e repassou o assinador")
    (is (= "Requerimento de informação" (:tipo-requerimento p)))
    (is (str/starts-with? (:texto p) "Ana Prado requer") "o 'vereador' forjado no corpo foi ignorado")
    (is (= ["CE" "Fortaleza"] [(:uf p) (:municipio-nome p)]))))

(deftest autor-no-corpo-e-descartado
  ;; os adapters do modulo so' leem os campos esperados (`so-esperados`): um `autor-id` forjado nem chega ao
  ;; dominio — a autoria continua sendo a do login.
  (let [protocolado (atom nil)
        r (pt/response-for (service-fn :protocolado protocolado) :post "/meu/requerimentos" :headers (headers)
                           :body (corpo {:modelo-id (str (:id modelo-req)) :campos campos :ementa "E"
                                         :autor-id (str (random-uuid)) :autor-texto "Outro Vereador"}))]
    (is (= 201 (:status r)))
    (is (= [vereador-id "Ana Prado"] [(:autor-id @protocolado) (:autor-texto @protocolado)]))))

(deftest sem-cadastro-de-vereador-404
  (let [svc (service-fn :tem-cadastro? false)]
    (is (= 404 (:status (pt/response-for svc :post "/meu/requerimentos/previa" :headers (headers)
                                         :body (corpo {:modelo-id (str (:id modelo-req)) :campos campos})))))
    (is (= 404 (:status (pt/response-for svc :post "/meu/requerimentos" :headers (headers)
                                         :body (corpo {:modelo-id (str (:id modelo-req)) :campos campos :ementa "E"})))))))

(deftest modelo-de-outro-tipo-404
  (is (= 404 (:status (pt/response-for (service-fn) :post "/meu/requerimentos" :headers (headers)
                                       :body (corpo {:modelo-id (str (:id modelo-oficio)) :campos {"x" "y"} :ementa "E"}))))))

(deftest corpo-invalido-400
  (let [svc (service-fn)
        post (fn [b] (:status (pt/response-for svc :post "/meu/requerimentos" :headers (headers) :body (corpo b))))]
    (is (= 400 (post {:modelo-id (str (:id modelo-req)) :campos campos})) "sem ementa")
    (is (= 400 (post {:modelo-id (str (:id modelo-req)) :campos campos :ementa "   "})) "ementa em branco")
    (is (= 400 (post {:modelo-id "nao-e-uuid" :campos campos :ementa "E"})))
    (is (= 400 (post {:modelo-id (str (:id modelo-req)) :campos {"assunto" "so um"} :ementa "E"}))
        "campo do modelo faltando -> 400 (fail-closed do renderizador)")))
