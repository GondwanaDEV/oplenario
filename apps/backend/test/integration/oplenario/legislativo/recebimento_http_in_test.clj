(ns oplenario.legislativo.recebimento-http-in-test
  "Fatia 2b — a BORDA do recebimento assinado da tramitacao: `POST /legislativo/proposicoes/:id/recebimento`,
  `GET /legislativo/recebimentos-pendentes` e o que muda no `GET .../tramitacao`. DB-free (Repo FAKE, mesmo
  racional de tramitacao-http-in-test): a regra (pendencia derivada, trava, DSL de quem recebe, assinatura,
  append-only) ja' tem integracao real em recebimento-tramitacao-db-test. Aqui, so' o que a borda decide:
  corpo fechado, quem recebe vem do token, e cada desfecho com o seu codigo e o seu `motivo`."
  (:require [clojure.test :refer [deftest is testing]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.autorizacao :as authz]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.rotas :as rotas])
  (:import (java.time Instant)))

(def ^:private agora (Instant/parse "2026-09-26T14:30:00Z"))

(defn- linha-proposicao [id]
  {:id id :tipo "projeto_lei" :ano 2026 :sequencial 7 :urn-lex "urn:lex:x" :ementa "X"
   :estado "em_comissoes" :template-id (random-uuid) :lock-version 0 :atualizado-em agora})

(defn- fake-repo-legislativo [{:keys [buscar receber pendentes tramitacao transicionar]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-leg/RepoLegislativo
    (buscar-proposicao [_ _ente-id id] (buscar id))
    (receber-movimentacao! [_ ente-id registro args] (receber ente-id registro args))
    (recebimentos-pendentes [_ ente-id] (pendentes ente-id))
    (tramitacao-da-proposicao [_ _ente-id pid limite] (tramitacao pid limite))
    (transicionar! [_ _ente-id registro args] (transicionar registro args))))

(defn- fake-repo-identidade
  "`nomes` = identidade-id -> nome de quem tem vinculo NESTA Casa (o seam `nome-na-casa` do host le' daqui)."
  [papeis nomes]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})
    (vinculos-de [_ _ente-id identidade-id] (when (contains? nomes identidade-id) [{:id (random-uuid)}]))
    (nome-por-id [_ id] (when-let [n (get nomes id)] {:nome n}))))

(defn- service-fn
  ([papeis repo-l] (service-fn papeis repo-l {}))
  ([papeis repo-l nomes]
   (-> (http/servico (config/carregar)
                     (rotas/montar {:idp (idp-dev/idp-dev)
                                    :repo-identidade (fake-repo-identidade papeis nomes)
                                    :repo-legislativo repo-l
                                    :registro-fatos :registro-fake
                                    :relogio (constantly agora)})
                     it/globais)
       ph/create-server ::ph/service-fn)))

(defn- token [ente-id ident-id]
  (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))
(defn- cabecalhos [tok] {"authorization" (str "Bearer " tok) "Content-Type" "application/json"})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(defn- post-recebimento
  ([repo-l pid corpo] (post-recebimento repo-l pid corpo #{"secretario"} (random-uuid) (random-uuid)))
  ([repo-l pid corpo papeis ente identidade]
   (pt/response-for (service-fn papeis repo-l)
                    :post (str "/legislativo/proposicoes/" pid "/recebimento")
                    :headers (cabecalhos (token ente identidade))
                    :body (json/write-value-as-string corpo))))

(defn- explode [& _] (throw (AssertionError. "o Repo NAO devia ter sido chamado")))

;; ============================ POST .../recebimento ============================

(deftest receber-201-com-recibo-assinado-e-quem-recebe-vem-do-token
  (let [pid (random-uuid) mov (random-uuid) ente (random-uuid) eu (random-uuid) recibo (random-uuid)
        visto (atom nil)
        repo (fake-repo-legislativo
               {:buscar linha-proposicao
                :receber (fn [ente-id _registro args]
                           (reset! visto (assoc args :ente-id ente-id))
                           {:id recibo :proposicao-id pid :transicao-id (:transicao-id args) :estado "em_comissoes"
                            :recebido-por (:identidade-id (:ator args)) :recebido-em agora
                            :assinatura-algoritmo "STUB-ICP-v0"})})
        r (post-recebimento repo pid {:movimentacao-id (str mov)} #{"secretario"} ente eu)
        b (ler-json r)]
    (is (= 201 (:status r)))
    (is (= {:id (str recibo) :proposicao-id (str pid) :movimentacao-id (str mov) :estado "em_comissoes"
            :recebido-em (str agora) :assinatura-algoritmo "STUB-ICP-v0"}
           b))
    (is (= mov (:transicao-id @visto)) "a movimentacao que a pessoa viu desce ate' o Repo")
    (is (= pid (:proposicao-id @visto)) "a materia vem do PATH")
    (is (= eu (:identidade-id (:ator @visto))) "quem recebe e' quem esta' logado, nunca o corpo")
    (is (= ente (:ente-id @visto)))
    (is (some? (:assinador @visto)) "o recebimento e' ASSINADO")))

(deftest corpo-fechado-e-uuid-valido
  (let [pid (random-uuid)
        repo (fake-repo-legislativo {:buscar explode :receber explode})]
    (testing "o cliente nao escolhe quem recebe, nem estado, nem hora — campo extra e' 400"
      (doseq [campo [:recebido-por :estado :recebido-em]]
        (is (= 400 (:status (post-recebimento repo pid {:movimentacao-id (str (random-uuid)) campo "x"})))
            (str "campo proibido: " campo))))
    (is (= 400 (:status (post-recebimento repo pid {}))) "sem movimentacao-id")
    (is (= 400 (:status (post-recebimento repo pid {:movimentacao-id "nao-e-um-uuid-nao-e-um-uuid-nao-e-u"}))))))

(deftest materia-inexistente-404-sem-tocar-no-recebimento
  (let [repo (fake-repo-legislativo {:buscar (constantly nil) :receber explode})
        r (post-recebimento repo (random-uuid) {:movimentacao-id (str (random-uuid))})]
    (is (= 404 (:status r)))))

(defn- post-com-excecao [e]
  (let [repo (fake-repo-legislativo {:buscar linha-proposicao :receber (fn [& _] (throw e))})
        r (post-recebimento repo (random-uuid) {:movimentacao-id (str (random-uuid))})]
    (assoc (ler-json r) :status (:status r))))

(deftest os-conflitos-saem-409-com-motivo-proprio
  (testing "nada a receber (ou ja' recebida — o duplo clique cai aqui e nao duplica o recibo)"
    (let [b (post-com-excecao (ex-info "x" {:tipo :conflito/sem-recebimento-pendente}))]
      (is (= 409 (:status b)))
      (is (= "sem-recebimento-pendente" (:motivo b)))))
  (testing "a materia andou depois que a tela carregou: nao se assina o que nao foi visto"
    (let [b (post-com-excecao (ex-info "x" {:tipo :conflito/movimentacao-divergente}))]
      (is (= 409 (:status b)))
      (is (= "movimentacao-divergente" (:motivo b)))
      (is (re-find #"atualize" (:erro b))))))

(deftest regra-de-quem-recebe-negada-403
  (let [b (post-com-excecao (try (authz/negar! "regra do rito" {}) (catch clojure.lang.ExceptionInfo e e)))]
    (is (= 403 (:status b)))
    (is (re-find #"receber" (:erro b)))))

(deftest so-quem-opera-o-expediente-recebe
  (let [repo (fake-repo-legislativo {:buscar explode :receber explode})
        r (post-recebimento repo (random-uuid) {:movimentacao-id (str (random-uuid))}
                            #{"vereador"} (random-uuid) (random-uuid))]
    (is (= 403 (:status r)) "gate grosso: papel secretario")))

;; ============================ GET /recebimentos-pendentes ============================

(deftest a-fila-de-pendentes-da-casa
  (let [ente (random-uuid) pid (random-uuid) mov (random-uuid) visto (atom nil)
        repo (fake-repo-legislativo
               {:pendentes (fn [ente-id]
                             (reset! visto ente-id)
                             [{:proposicao-id pid :tipo "projeto_lei" :sequencial 7 :ano 2026 :ementa "X"
                               :estado "em_comissoes" :estado-nome "Em Comissões" :transicao-id mov
                               :de-estado "protocolada" :desde agora :restrito true}])})
        r (pt/response-for (service-fn #{"secretario"} repo) :get "/legislativo/recebimentos-pendentes"
                           :headers (cabecalhos (token ente (random-uuid))))]
    (is (= 200 (:status r)))
    (is (= ente @visto) "a fila e' a da Casa do token")
    (is (= [{:proposicao-id (str pid) :tipo "projeto_lei" :sequencial 7 :ano 2026 :ementa "X"
             :estado "em_comissoes" :estado-nome "Em Comissões" :movimentacao-id (str mov)
             :de-estado "protocolada" :desde (str agora) :restrito true}]
           (:itens (ler-json r))))))

;; ============================ GET .../tramitacao: a carga e o recibo ============================

(deftest a-leitura-da-tramitacao-mostra-a-carga-e-quem-recebeu
  (let [pid (random-uuid) mov-1 (random-uuid) mov-2 (random-uuid) servidora (random-uuid) forasteiro (random-uuid)
        linha (linha-proposicao pid)
        repo (fake-repo-legislativo
               {:tramitacao
                (fn [_pid _limite]
                  {:proposicao linha
                   :historico [{:id mov-1 :de-estado "protocolada" :para-estado "em_comissoes" :gatilho "despachar"
                                :ocorrido-em agora}
                               {:id mov-2 :de-estado "em_comissoes" :para-estado "em_comissoes" :gatilho "redistribuir"
                                :ocorrido-em agora}]
                   :candidatas []
                   :estado-no-template {:chave "em_comissoes" :terminal false}
                   :recebimentos {mov-1 {:recebido-por servidora :recebido-em agora :assinatura-algoritmo "STUB-ICP-v0"}
                                  mov-2 {:recebido-por forasteiro :recebido-em agora :assinatura-algoritmo "STUB-ICP-v0"}}
                   :recebimento-pendente nil})})
        r (pt/response-for (service-fn #{"secretario"} repo {servidora "Maria Servidora"})
                           :get (str "/legislativo/proposicoes/" pid "/tramitacao")
                           :headers (cabecalhos (token (random-uuid) (random-uuid))))
        b (ler-json r)]
    (is (= 200 (:status r)))
    (is (= {:recebido-por-nome "Maria Servidora" :recebido-em (str agora) :assinatura-algoritmo "STUB-ICP-v0"}
           (:recebimento (first (:historico b)))))
    (is (nil? (:recebido-por-nome (:recebimento (second (:historico b)))))
        "quem nao tem vinculo nesta Casa nao tem o nome publicado — a tela diz 'recebida', sem inventar")
    (is (nil? (:recebimento-pendente b)))))

(deftest a-carga-pendente-aparece-na-leitura
  (let [pid (random-uuid) mov (random-uuid)
        repo (fake-repo-legislativo
               {:tramitacao
                (fn [_pid _limite]
                  {:proposicao (linha-proposicao pid)
                   :historico [{:id mov :de-estado "protocolada" :para-estado "em_comissoes" :gatilho "despachar"
                                :ocorrido-em agora}]
                   :candidatas []
                   :estado-no-template {:chave "em_comissoes" :terminal false}
                   :recebimentos {}
                   :recebimento-pendente {:proposicao-id pid :transicao-id mov :de-estado "protocolada"
                                          :estado "em_comissoes" :estado-nome "Em Comissões" :desde agora
                                          :recebedor "\"comissao\" in ator.papeis"}})})
        b (ler-json (pt/response-for (service-fn #{"secretario"} repo)
                                     :get (str "/legislativo/proposicoes/" pid "/tramitacao")
                                     :headers (cabecalhos (token (random-uuid) (random-uuid)))))]
    (is (= {:movimentacao-id (str mov) :de-estado "protocolada" :estado "em_comissoes" :estado-nome "Em Comissões"
            :desde (str agora) :restrito true}
           (:recebimento-pendente b))
        "a regra de quem recebe NAO vaza — so' o aviso de que ela existe")
    (is (nil? (:recebimento (first (:historico b)))))))

;; ============================ POST .../tramitacao: a carga trava o ato ============================

(deftest tramitar-com-carga-nao-recebida-e-409-recebimento-pendente
  (let [pid (random-uuid)
        repo (fake-repo-legislativo
               {:buscar linha-proposicao
                :transicionar (fn [_ _] {:transicionou? false :motivo :recebimento-pendente
                                         :de "em_comissoes" :gatilho "concluir"})})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post (str "/legislativo/proposicoes/" pid "/tramitacao")
                           :headers (cabecalhos (token (random-uuid) (random-uuid)))
                           :body (json/write-value-as-string {:gatilho "concluir"}))
        b (ler-json r)]
    (is (= 409 (:status r)))
    (is (= "recebimento-pendente" (:motivo b)))
    (is (re-find #"RECEBIDA" (:erro b)))))
