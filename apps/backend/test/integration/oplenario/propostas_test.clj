(ns oplenario.propostas-test
  "INTEGRACAO (PG real): B.6 / ADR-0012 — a PROPOSTA DE ATO. O agente pede `protocolar_requerimento`: nada e'
  protocolado, nasce uma proposta com o texto exato; so' a pessoa, na tela, confirma — e ai' a mesma entrada roda
  como ela, com o papel conferido na hora. Uma confirmacao so' (a segunda e' conflito), recusa encerra, prazo expira,
  a leitura de terceiro da execucao aparece na proposta, e o agente nunca confirma."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [oplenario.catalogo :as catalogo]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.integracao-ia.components.repositorio :as repo-ia]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.migracao :as migracao]
            [oplenario.propostas :as propostas])
  (:import (java.time Duration Instant)))

(def ^:dynamic *c* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*c* c] (try (t) (finally (component/stop c)))))))

(def ^:private template
  "REQUERIMENTO\n\n{{vereador}} requer a {{destinatario}} informacoes sobre {{assunto}}.\n\nFortaleza, {{data}}.")

(def ^:private instante (atom (Instant/parse "2026-09-27T15:00:00Z")))
(def ^:private relogio (reify tempo/Relogio (agora [_] @instante)))

(defn- repo-legislativo [] (repo-leg/->RepoLegislativoPg *c* (outbox/bus)))
(defn- repo-integracao [] (repo-ia/map->RepoIntegracaoIAPg {:datasource *c*}))

(defn- cenario []
  (let [ente (random-uuid) pessoa (random-uuid) vereador (random-uuid) mid (random-uuid)
        rl (repo-legislativo) ri (repo-integracao)]
    (repo-leg/criar-modelo! rl ente {:id mid :chave (str "m-" mid) :nome "Requerimento de informação"
                                     :tipo-documento "requerimento_proposicao" :corpo-template template
                                     :created-by (random-uuid)})
    {:ente ente :pessoa pessoa :modelo mid :repo-ia ri
     :deps {:repo-legislativo rl
            :resolver-autor (fn [e i] (when (and (= e ente) (= i pessoa)) {:id vereador :nome "Ana Prado"}))
            :resolver-municipio (constantly {:uf "CE" :municipio-nome "Fortaleza"})
            :relogio relogio
            :registrar-chamada (catalogo/registrador ri)
            :propor (propostas/propositor ri relogio)
            :marcar-terceiro (propostas/marcador-de-terceiro ri)}}))

(defn- agente [{:keys [ente pessoa]} & {:keys [execucao] :or {execucao (random-uuid)}}]
  {:identidade-id pessoa :ente-id ente :papeis #{"vereador"}
   :via {:agente "assistente-da-casa" :execucao-id execucao :publico :vereador :classes #{:leitura :ato}
         :institucional? false}})

(defn- tela [{:keys [ente pessoa]}] {:identidade-id pessoa :ente-id ente :papeis #{"vereador"}})

(defn- pedido [{:keys [modelo]}]
  {"modelo-id" (str modelo) "ementa" "Informações sobre a obra da praça"
   "campos" {"destinatario" "Secretaria de Obras" "assunto" "a obra da praça"}})

(defn- deps-tela [{:keys [deps repo-ia]}] {:repo-integracao-ia repo-ia :relogio relogio :deps-catalogo deps})

(defn- proposicoes [ente]
  (:n (jdbc/execute-one! (:ds *c*) ["SELECT count(*)::int AS n FROM legislativo.proposicoes WHERE ente_id = ?" ente]
                         {:builder-fn next.jdbc.result-set/as-unqualified-maps})))

(defn- tipo-do-erro [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:tipo (ex-data e)))))

(deftest o-agente-propoe-e-a-pessoa-confirma
  (let [{:keys [ente deps repo-ia] :as c} (cenario)
        exec (random-uuid)
        modelos (catalogo/executar! deps (agente c :execucao exec) "modelos_de_requerimento" {})
        r (catalogo/executar! deps (agente c :execucao exec) "protocolar_requerimento" (pedido c))
        pid (parse-uuid (:proposta-id r))]
    (testing "o agente le os modelos e so' PROPOE: nada foi protocolado"
      (is (= ["destinatario" "assunto"] (:campos (first (:itens modelos)))))
      (is (= "aguardando_confirmacao" (:estado r)))
      (is (re-find #"Nada foi feito" (:mensagem r)))
      (is (= 0 (proposicoes ente)))
      (is (= [["protocolar_requerimento" "ato" "proposta"]]
             (mapv (juxt :ferramenta :classe :desfecho) (repo-ia/chamadas-da-execucao repo-ia ente exec)))
          "o audit registra a tentativa de ato como proposta"))
    (testing "a proposta guarda o texto exato, o ritual e a entrada validada"
      (let [p (repo-ia/proposta repo-ia ente pid)]
        (is (= "aguardando" (:estado p)))
        (is (= "assinatura" (:ritual p)))
        (is (= "Protocolar o requerimento “Informações sobre a obra da praça”" (:titulo p)))
        (is (re-find #"Ana Prado requer a Secretaria de Obras informacoes sobre a obra da praça" (:texto p)))
        (is (= exec (:execucao-id p)))
        (is (= (str (:modelo c)) (get-in p [:entrada "modelo-id"])))
        (is (= [pid] (mapv :id (repo-ia/propostas-da-execucao repo-ia ente exec))))))
    (testing "ver: a pessoa le a proposta e o que seria feito agora; outra pessoa nem acha; o agente e' barrado"
      (let [v (propostas/ver (deps-tela c) (tela c) pid)]
        (is (re-find #"Ana Prado requer" (get-in v [:apresentacao-atual :texto]))))
      (is (nil? (propostas/ver (deps-tela c) (assoc (tela c) :identidade-id (random-uuid)) pid)))
      (is (= :autorizacao/negado (tipo-do-erro #(propostas/ver (deps-tela c) (agente c) pid))))
      (is (= :autorizacao/negado (tipo-do-erro #(propostas/confirmar! (deps-tela c) (agente c) pid)))
          "o agente nunca confirma o que propos"))
    (testing "confirmar: o requerimento e' protocolado e assinado, como a pessoa"
      (let [p (propostas/confirmar! (deps-tela c) (tela c) pid)]
        (is (= "confirmada" (:estado p)))
        (is (some? (:decidida-em p)))
        (is (= "STUB-ICP-v0" (get-in p [:resultado :assinatura-algoritmo])))
        (is (= 1 (proposicoes ente)))))
    (testing "uma confirmacao so': a segunda e a recusa depois sao conflito"
      (is (= :proposta/decidida (tipo-do-erro #(propostas/confirmar! (deps-tela c) (tela c) pid))))
      (is (= :proposta/decidida (tipo-do-erro #(propostas/recusar! (deps-tela c) (tela c) pid))))
      (is (= 1 (proposicoes ente))))))

(deftest recusa-prazo-e-papel
  (let [{:keys [ente deps] :as c} (cenario)
        propor! #(parse-uuid (:proposta-id (catalogo/executar! deps (agente c) "protocolar_requerimento" (pedido c))))]
    (testing "recusar encerra, e nada e' protocolado"
      (let [pid (propor!)]
        (is (= "recusada" (:estado (propostas/recusar! (deps-tela c) (tela c) pid))))
        (is (= :proposta/decidida (tipo-do-erro #(propostas/confirmar! (deps-tela c) (tela c) pid))))
        (is (= 0 (proposicoes ente)))))
    (testing "a lista mostra so' as que esperam, da pessoa"
      (let [pid (propor!)]
        (is (= [pid] (mapv :id (propostas/listar (deps-tela c) (tela c)))))
        (is (empty? (propostas/listar (deps-tela c) (assoc (tela c) :identidade-id (random-uuid)))))
        (testing "o papel e' conferido na confirmacao: sem 'vereador' agora, negado — e a proposta volta a esperar"
          (is (= :autorizacao/negado
                 (tipo-do-erro #(propostas/confirmar! (deps-tela c) (assoc (tela c) :papeis #{"secretario"}) pid))))
          (is (= "aguardando" (:estado (propostas/ver (deps-tela c) (tela c) pid))))
          (is (= 0 (proposicoes ente))))
        (testing "72 h depois, expira"
          (swap! instante #(.plus ^Instant % (Duration/ofHours 73)))
          (try
            (is (= "expirada" (:estado (propostas/ver (deps-tela c) (tela c) pid))))
            (is (= :proposta/expirada (tipo-do-erro #(propostas/confirmar! (deps-tela c) (tela c) pid))))
            (is (empty? (propostas/listar (deps-tela c) (tela c))))
            (finally (swap! instante #(.minus ^Instant % (Duration/ofHours 73))))))))
    (testing "modelo que nao existe nao vira proposta"
      (is (nil? (catalogo/executar! deps (agente c) "protocolar_requerimento"
                                    (assoc (pedido c) "modelo-id" (str (random-uuid)))))))))

(deftest leitura-de-terceiro-vai-para-a-proposta
  (let [{:keys [ente deps repo-ia] :as c} (cenario)
        exec (random-uuid)
        ator (agente c :execucao exec)]
    ((:marcar-terceiro deps) ator {:nome "pedido_esic"} [{:origem "e-SIC" :referencia "nº 12/2026"}])
    (let [r (catalogo/executar! deps ator "protocolar_requerimento" (pedido c))
          p (repo-ia/proposta repo-ia ente (parse-uuid (:proposta-id r)))]
      (is (= [{:ferramenta "pedido_esic" :origem "e-SIC" :referencia "nº 12/2026"}] (:contaminada-por p))
          "o confirmador ve 'feita depois de ler e-SIC nº 12/2026' (Eixo 4.5)"))))

(deftest institucional-nunca-propoe
  (let [{:keys [deps] :as c} (cenario)]
    (is (= :autorizacao/negado
           (tipo-do-erro #(catalogo/executar! deps (-> (agente c) (assoc-in [:via :institucional?] true))
                                              "protocolar_requerimento" (pedido c)))))))

;; ---------- a borda HTTP da tela ----------

(defn- fake-identidade []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ _] {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis #{"vereador"}})))

(defn- servico [c]
  (-> (http/servico (config/carregar)
                    (propostas/rotas (assoc (deps-tela c) :auth (it/autenticacao (idp-dev/idp-dev) (fake-identidade))))
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- pedir [svc metodo caminho {:keys [ente pessoa]}]
  (let [r (pt/response-for svc metodo caminho
                           :headers {"Authorization" (str "Bearer " (json/write-value-as-string
                                                                     {:sub "u" :ente-id (str ente)
                                                                      :identidade-id (str pessoa)}))
                                     "Content-Type" "application/json"}
                           :body (when (= :post metodo) "{}"))]
    {:status (:status r) :corpo (when (seq (:body r)) (json/read-value (:body r) json/keyword-keys-object-mapper))}))

(deftest a-tela-das-propostas
  (let [{:keys [deps] :as c} (cenario)
        svc (servico c)
        pid (:proposta-id (catalogo/executar! deps (agente c) "protocolar_requerimento" (pedido c)))]
    (is (= [pid] (mapv :id (:itens (:corpo (pedir svc :get "/propostas" c))))))
    (let [{:keys [status corpo]} (pedir svc :get (str "/propostas/" pid) c)]
      (is (= 200 status))
      (is (= "assinatura" (:ritual corpo)))
      (is (re-find #"Ana Prado requer" (get-in corpo [:apresentacao-atual :texto]))))
    (is (= 404 (:status (pedir svc :get (str "/propostas/" (random-uuid)) c))))
    (is (= 200 (:status (pedir svc :post (str "/propostas/" pid "/confirmacao") c))))
    (let [{:keys [status corpo]} (pedir svc :post (str "/propostas/" pid "/confirmacao") c)]
      (is (= 409 status))
      (is (= "decidida" (:causa corpo))))))

(deftest o-que-o-agente-ve
  (let [c (cenario)
        nomes #(set (map :nome (catalogo/ferramentas %)))]
    (is (every? (nomes (agente c)) ["modelos_de_requerimento" "protocolar_requerimento"]))
    (is (= "aguardando_confirmacao"
           (first (get-in (some #(when (= "protocolar_requerimento" (:nome %)) %) (catalogo/ferramentas (agente c)))
                          [:saida :properties :estado :enum])))
        "para o agente, protocolar devolve a proposta")
    (is (not-any? #{"protocolar_requerimento"} (nomes (assoc-in (agente c) [:via :classes] #{:leitura})))
        "execucao so' de leitura nem ve o ato")
    (is (not-any? #{"protocolar_requerimento" "modelos_de_requerimento"}
                  (nomes (-> (agente c) (assoc :papeis #{"secretario"}) (assoc-in [:via :publico] :secretaria))))
        "o requerimento e' do proprio vereador")))
