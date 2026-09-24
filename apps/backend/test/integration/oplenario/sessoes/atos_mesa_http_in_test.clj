(ns oplenario.sessoes.atos-mesa-http-in-test
  "docs/23 Fatia 2 — `GET /sessoes/:id/atos-mesa`: a LEITURA dos atos da Mesa (decisoes sobre questao de ordem +
  incidentes processuais) que o cockpit usa para mostrar o que ja registrou. As duas escritas sao append-only e
  nao tinham leitura exposta. Papel 'secretario' na borda; camada fina pode-ver-sessao? no controller (outra
  Casa -> 403); sessao inexistente -> 404; :id malformado -> 400. `created-by` NAO sai (auditoria, nao ata).
  DB-free: RepoSessoes FAKE + idp-dev real — espelha tribuna-decisao-http-in-test."
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.rotas :as rotas]
            [oplenario.sessoes.components.repositorio :as repo-sessoes])
  (:import (java.time Instant)))

(defn- sessao-canonica [ente-id id]
  {:id id :ente-id ente-id :estado "encerrada" :tipo-sessao "ordinaria"})

(defn- fake-repo-sessoes
  "RepoSessoes fake (parcial): `buscar-sessao` resolve a sessao; as duas listagens devolvem o que o teste manda
  e registram a sessao-id pedida em `pedidas`."
  [busca-fn {:keys [decisoes incidentes pedidas]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ ente-id id] (busca-fn ente-id id))
    (listar-decisoes-mesa [_ _ente-id sessao-id] (swap! pedidas conj [:decisoes sessao-id]) decisoes)
    (listar-incidentes [_ _ente-id sessao-id] (swap! pedidas conj [:incidentes sessao-id]) incidentes)))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- service-fn* [papeis repo-s]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-sessoes repo-s
                                   :objeto-store nil})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id]
  (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))
(defn- auth [tok] {"authorization" (str "Bearer " tok)})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))
(defn- url [sid] (str "/sessoes/" sid "/atos-mesa"))

(deftest atos-mesa-200-projeta-decisoes-e-incidentes
  (let [ente (random-uuid) sid (random-uuid) pres (random-uuid) operador (random-uuid)
        dec-id (random-uuid) fala (random-uuid) inc-id (random-uuid) inc2 (random-uuid) prop (random-uuid)
        pedidas (atom [])
        repo-s (fake-repo-sessoes
                (fn [_ id] (sessao-canonica ente id))
                {:pedidas pedidas
                 :decisoes [{:id dec-id :sessao-id sid :presidente-id pres :created-by operador
                             :questao "Cabe aparte na fala do lider?" :decisao "Indeferida"
                             :fundamentacao "Art. 90 do Regimento" :fala-id fala
                             :decidido-em (Instant/parse "2026-09-24T14:05:00Z")}]
                 :incidentes [{:id inc-id :sessao-id sid :tipo "pedido_vista" :resultado "deferido"
                               :descricao "Vista do PL 22/2026 ao Ver. Fulano" :objeto-tipo "proposicao"
                               :objeto-id prop :requerente-id pres :deliberacao "Prazo de uma sessao"
                               :ocorrido-em (Instant/parse "2026-09-24T14:10:00Z")}
                              {:id inc2 :sessao-id sid :tipo "urgencia" :resultado "indeferido"
                               :descricao "Pedido de urgencia"
                               :ocorrido-em (Instant/parse "2026-09-24T14:20:00Z")}]})
        r (pt/response-for (service-fn* #{"secretario"} repo-s) :get (url sid) :headers (auth (token ente operador)))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= (str sid) (:sessao-id body)))
    (is (= [{:id (str dec-id) :presidente-id (str pres) :questao "Cabe aparte na fala do lider?"
             :decisao "Indeferida" :fundamentacao "Art. 90 do Regimento" :fala-id (str fala)
             :decidido-em "2026-09-24T14:05:00Z"}]
           (:decisoes body))
        "decisao projetada; created-by NAO sai")
    (is (= {:id (str inc-id) :tipo "pedido_vista" :resultado "deferido"
            :descricao "Vista do PL 22/2026 ao Ver. Fulano" :objeto-tipo "proposicao" :objeto-id (str prop)
            :requerente-id (str pres) :deliberacao "Prazo de uma sessao" :ocorrido-em "2026-09-24T14:10:00Z"}
           (first (:incidentes body))))
    (is (= {:id (str inc2) :tipo "urgencia" :resultado "indeferido" :descricao "Pedido de urgencia"
            :ocorrido-em "2026-09-24T14:20:00Z"}
           (second (:incidentes body)))
        "opcionais ausentes nao viram null: somem")
    (is (= #{[:decisoes sid] [:incidentes sid]} (set @pedidas)) "as duas leituras sao da sessao do path")))

(deftest atos-mesa-sessao-sem-atos-200-listas-vazias
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) {:pedidas (atom []) :decisoes [] :incidentes []})
        r (pt/response-for (service-fn* #{"secretario"} repo-s) :get (url sid) :headers (auth (token ente (random-uuid))))]
    (is (= 200 (:status r)))
    (is (= {:sessao-id (str sid) :decisoes [] :incidentes []} (ler-json r)))))

(deftest atos-mesa-sessao-inexistente-404
  (let [repo-s (fake-repo-sessoes (fn [_ _] nil) {:pedidas (atom [])})
        r (pt/response-for (service-fn* #{"secretario"} repo-s) :get (url (random-uuid))
                           :headers (auth (token (random-uuid) (random-uuid))))]
    (is (= 404 (:status r)))))

(deftest atos-mesa-casa-alheia-403-sem-ler-nada
  (let [pedidas (atom [])
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica (random-uuid) id)) {:pedidas pedidas})
        r (pt/response-for (service-fn* #{"secretario"} repo-s) :get (url (random-uuid))
                           :headers (auth (token (random-uuid) (random-uuid))))]
    (is (= 403 (:status r)) "sessao de outra Casa -> 403")
    (is (empty? @pedidas) "a authz roda ANTES de qualquer leitura dos atos")))

(deftest atos-mesa-sem-papel-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) {:pedidas (atom [])})
        r (pt/response-for (service-fn* #{"vereador"} repo-s) :get (url (random-uuid))
                           :headers (auth (token ente (random-uuid))))]
    (is (= 403 (:status r)) "leitura operacional da Mesa: sem papel 'secretario' -> 403")))

(deftest atos-mesa-id-malformado-400
  (let [repo-s (fake-repo-sessoes (fn [_ _] nil) {:pedidas (atom [])})
        r (pt/response-for (service-fn* #{"secretario"} repo-s) :get (url "nao-e-uuid")
                           :headers (auth (token (random-uuid) (random-uuid))))]
    (is (= 400 (:status r)))))

(deftest atos-mesa-sem-token-401
  (let [repo-s (fake-repo-sessoes (fn [_ _] nil) {:pedidas (atom [])})
        r (pt/response-for (service-fn* #{"secretario"} repo-s) :get (url (random-uuid)))]
    (is (= 401 (:status r)))))
