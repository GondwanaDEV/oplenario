(ns oplenario.tempo-real.sse-borda-test
  "§22.6 eixo G (G3) — a BORDA do endpoint SSE GET /sessoes/:id/plenario, caminhos de RECUSA. Estes terminam a
  cadeia ANTES do switch async (start-event-stream), entao `pt/response-for` os exercita sem travar no stream.
  Prova o checklist de authz na ABERTURA (canais.clj): sem token -> 401; sessao inexistente -> 404; sessao
  secreta / de outra Casa -> 403; :id malformado -> 400. DB-free: RepoSessoes fake + idp-dev real (precedente
  W2/W3). O caminho de SUCESSO (streaming) e' coberto pelos unit (sse_test) — response-for travaria no async."
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
            [oplenario.sessoes.components.repositorio :as repo-sessoes]
            [oplenario.tempo-real.components :as trc]))

(defn- sessao-canonica [ente-id id transmite?]
  {:id id :ente-id ente-id :sessao-legislativa-id (random-uuid) :tipo-sessao "ordinaria"
   :numero-sequencial 1 :estado "aberta" :modalidade "presencial" :delibera true
   :transmite-publica transmite? :gera-ata-regimental true :permite-voto-secreto false
   :permite-modalidade-remota true :lock-version 0})

(defn- fake-repo-sessoes [busca-fn]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ ente-id id] (busca-fn ente-id id))))

(defn- fake-repo-identidade []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "vereador"} :papeis #{}})))

(defn- service-fn [busca-fn]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade)
                                   :repo-sessoes (fake-repo-sessoes busca-fn)
                                   :canal-store (trc/canal-store-memoria)})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id]
  (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))

(defn- com-bearer [tok] {"authorization" (str "Bearer " tok)})

(deftest plenario-sem-token-401
  (let [r (pt/response-for (service-fn (fn [e i] (sessao-canonica e i true)))
                           :get (str "/sessoes/" (random-uuid) "/plenario"))]
    (is (= 401 (:status r)) "o endpoint SSE herda a cadeia de auth: sem token -> 401 (fail-closed)")))

(deftest plenario-id-malformado-400
  (let [ente (random-uuid)
        r (pt/response-for (service-fn (fn [e i] (sessao-canonica e i true)))
                           :get "/sessoes/nao-e-uuid/plenario" :headers (com-bearer (token ente (random-uuid))))]
    (is (= 400 (:status r)) "path-param :id malformado -> 400 (requisicao invalida), nunca 500")))

(deftest plenario-inexistente-404
  (let [ente (random-uuid)
        r (pt/response-for (service-fn (fn [_ _] nil))
                           :get (str "/sessoes/" (random-uuid) "/plenario")
                           :headers (com-bearer (token ente (random-uuid))))]
    (is (= 404 (:status r)) "sessao inexistente no tenant (repo nil) -> 404 (existir != poder ler)")))

(deftest plenario-secreta-403
  (let [ente (random-uuid)
        r (pt/response-for (service-fn (fn [e i] (sessao-canonica e i false)))  ; transmite_publica=false
                           :get (str "/sessoes/" (random-uuid) "/plenario")
                           :headers (com-bearer (token ente (random-uuid))))]
    (is (= 403 (:status r)) "sessao secreta -> RECUSA a subscricao (item 2 do checklist), nao filtra por evento")))

(deftest plenario-cross-ente-403
  ;; o recurso carregado pertence a OUTRO ente (escapou da RLS por bug hipotetico): o checklist (item 1, posse
  ;; de tenant, defesa-em-profundidade) tem de NEGAR -> 403.
  (let [ente (random-uuid)
        r (pt/response-for (service-fn (fn [_ id] (sessao-canonica (random-uuid) id true)))
                           :get (str "/sessoes/" (random-uuid) "/plenario")
                           :headers (com-bearer (token ente (random-uuid))))]
    (is (= 403 (:status r)) "sessao de Casa alheia -> posse de tenant nega -> 403")))
