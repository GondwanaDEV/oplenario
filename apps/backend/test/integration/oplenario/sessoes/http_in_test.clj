(ns oplenario.sessoes.http-in-test
  "W3 (frente wire/HTTP) — a PRIMEIRA vertical de rota por modulo (sessoes): prova a silhueta de borda
  end-to-end (wire/in -> adapters/in -> controller -> repo -> adapters/out -> wire/out) + as duas camadas de
  authz (grossa exige-papel no POST; fina policy.check com o recurso carregado no GET) + 404/400. DB-free: usa
  um RepoSessoes FAKE (reify) + idp-dev real, como o http-test de W2 — o repo real ja tem cobertura no F4."
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
            [oplenario.sessoes.components.repositorio :as repo-sessoes]))

(defn- sessao-canonica
  "Mapa de sessao como o `db/sessao buscar` devolve (kebab, schema-qualified ja desfeito)."
  [ente-id id]
  {:id id :ente-id ente-id :sessao-legislativa-id (random-uuid)
   :tipo-sessao "ordinaria" :numero-sequencial 1 :estado "agendada" :modalidade "presencial"
   :delibera true :transmite-publica true :gera-ata-regimental true
   :permite-voto-secreto false :permite-modalidade-remota true
   :agendada-para nil :aberta-em nil :encerrada-em nil :motivo-nao-realizada nil :lock-version 0})

(defn- fake-repo-sessoes
  "RepoSessoes fake: `buscar-sessao` devolve o que `busca-fn` retornar (ou nil); `agendar-sessao!` ecoa um
  recibo. Impl parcial proposital (so os metodos exercidos por W3)."
  [busca-fn]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ ente-id id] (busca-fn ente-id id))
    (agendar-sessao! [_ _ente-id m] {:id (:id m) :numero 7})))

(defn- fake-repo-pauta
  "RepoSessoes fake p/ a rota de pauta (GET /sessoes/:id/pauta): `buscar-sessao` via busca-fn (a authz mora no
  recurso sessao); `buscar-pauta-por-sessao` devolve `pauta` (ou nil = sessao sem pauta); `listar-itens` devolve
  `itens`. Impl parcial proposital (so os metodos exercidos pela leitura de pauta)."
  [busca-fn pauta itens]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ ente-id id] (busca-fn ente-id id))
    (buscar-pauta-por-sessao [_ _ente-id _sessao-id] pauta)
    (listar-itens [_ _ente-id _pauta-sessao-id] itens)))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- service-fn*
  "Monta o service-fn com um RepoSessoes ja construido (deixa cada teste injetar o fake que precisa)."
  [papeis repo-s]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-sessoes repo-s})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- service-fn [papeis busca-fn]
  (service-fn* papeis (fake-repo-sessoes busca-fn)))

(defn- token [ente-id ident-id]
  (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))

(defn- com-bearer [tok] {"authorization" (str "Bearer " tok)})

(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

;; ---------- GET /sessoes/:id — read path (wire/out + adapters/out + policy.check fina) ----------

(deftest buscar-sessao-200
  (let [ente (random-uuid) id (random-uuid)
        r (pt/response-for (service-fn #{} (fn [_ _] (sessao-canonica ente id)))
                           :get (str "/sessoes/" id) :headers (com-bearer (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)) "GET /sessoes/:id com token valido -> 200")
    (is (= (str id) (:id body)) "o id volta como string (uuid projetado pelo adapters/out)")
    (is (= "ordinaria" (:tipo-sessao body)))
    (is (= 1 (:numero-sequencial body)))
    (is (= "agendada" (:estado body)))
    (is (false? (:permite-voto-secreto body)))
    (is (= 0 (:lock-version body))
        "ledger de prontidao Fase 8 achado #2: GET /sessoes/:id e' a UNICA fonte do lock-version que POST /sessoes/:id/transicao exige no corpo -- sem ele a 2a chamada do fluxo e' impossivel")))

(deftest buscar-sessao-inexistente-404
  (let [ente (random-uuid)
        r (pt/response-for (service-fn #{} (fn [_ _] nil))
                           :get (str "/sessoes/" (random-uuid)) :headers (com-bearer (token ente (random-uuid))))]
    (is (= 404 (:status r)) "sessao inexistente (repo devolve nil) -> 404")))

(deftest buscar-sessao-sem-token-401
  (let [r (pt/response-for (service-fn #{} (fn [e i] (sessao-canonica e i)))
                           :get (str "/sessoes/" (random-uuid)))]
    (is (= 401 (:status r)) "rota de modulo herda a cadeia de auth: sem token -> 401 (fail-closed)")))

(deftest buscar-sessao-policy-fina-nega-403
  ;; o recurso carregado pertence a OUTRO ente (escapou da RLS por bug hipotetico): a camada FINA
  ;; (policy.check/pode-ver-sessao?) tem de NEGAR -> 403, provando que o seam in-domain morde.
  (let [ente (random-uuid)
        r (pt/response-for (service-fn #{} (fn [_ id] (sessao-canonica (random-uuid) id)))
                           :get (str "/sessoes/" (random-uuid)) :headers (com-bearer (token ente (random-uuid))))]
    (is (= 403 (:status r)) "recurso de ente alheio -> policy.check nega -> 403")))

(deftest buscar-sessao-id-malformado-400
  (let [ente (random-uuid)
        r (pt/response-for (service-fn #{} (fn [e i] (sessao-canonica e i)))
                           :get "/sessoes/nao-e-uuid" :headers (com-bearer (token ente (random-uuid))))]
    (is (= 400 (:status r)) "path-param :id malformado -> 400 (requisicao invalida), nunca 500")))

;; ---------- GET /sessoes/:id/pauta — pauta viva (read; authz herdada da sessao) ----------

(defn- pauta-canonica [ente-id ps-id sessao-id]
  {:id ps-id :ente-id ente-id :sessao-id sessao-id})

(defn- item-canonico [ente-id ps-id ordem tipo-item proposicao-id texto]
  {:id (random-uuid) :ente-id ente-id :pauta-sessao-id ps-id :fase "ordem_do_dia"
   :tipo-item tipo-item :proposicao-id proposicao-id :texto-descricao texto :ordem ordem
   :ativo true :lock-version 0})

(deftest pauta-da-sessao-200
  (let [ente (random-uuid) id (random-uuid) ps (random-uuid)
        prop (random-uuid)
        itens [(item-canonico ente ps 1 "proposicao" prop nil)
               (item-canonico ente ps 2 "comunicado" nil "Comunicado da Mesa")]
        repo (fake-repo-pauta (fn [_ _] (sessao-canonica ente id)) (pauta-canonica ente ps id) itens)
        r (pt/response-for (service-fn* #{} repo)
                           :get (str "/sessoes/" id "/pauta") :headers (com-bearer (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)) "GET /sessoes/:id/pauta com token valido -> 200")
    (is (= (str id) (:sessao-id body)) "a pauta carrega o sessao-id (string)")
    (is (= 2 (count (:itens body))) "os dois itens ativos voltam")
    (is (= "proposicao" (:tipo-item (first (:itens body)))))
    (is (= (str prop) (:proposicao-id (first (:itens body)))) "proposicao-id projetado como string")
    (is (= "Comunicado da Mesa" (:texto-descricao (second (:itens body)))))
    (is (= 0 (:lock-version (first (:itens body))))
        "ledger de prontidao Fase 8 achado #2: GET .../pauta e' a UNICA fonte do lock-version que PATCH/DELETE .../pauta/itens/:item-id exigem no corpo")
    (is (not (contains? (first (:itens body)) :ente-id)) "ente-id nao vaza")
    (is (not (contains? (first (:itens body)) :pauta-sessao-id)) "pauta-sessao-id interno nao vaza")))

(deftest pauta-da-sessao-sem-pauta-200-vazia
  (let [ente (random-uuid) id (random-uuid)
        repo (fake-repo-pauta (fn [_ _] (sessao-canonica ente id)) nil [])
        r (pt/response-for (service-fn* #{} repo)
                           :get (str "/sessoes/" id "/pauta") :headers (com-bearer (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)) "sessao existente sem pauta criada -> 200 (pauta opcional)")
    (is (= [] (:itens body)) "itens vazios quando nao ha pauta")))

(deftest pauta-da-sessao-inexistente-404
  (let [ente (random-uuid)
        repo (fake-repo-pauta (fn [_ _] nil) nil [])
        r (pt/response-for (service-fn* #{} repo)
                           :get (str "/sessoes/" (random-uuid) "/pauta") :headers (com-bearer (token ente (random-uuid))))]
    (is (= 404 (:status r)) "sessao inexistente -> 404 (sem sessao nao ha pauta)")))

(deftest pauta-da-sessao-sem-token-401
  (let [repo (fake-repo-pauta (fn [e i] (sessao-canonica e i)) nil [])
        r (pt/response-for (service-fn* #{} repo) :get (str "/sessoes/" (random-uuid) "/pauta"))]
    (is (= 401 (:status r)) "rota herda a cadeia de auth: sem token -> 401")))

(deftest pauta-da-sessao-policy-fina-nega-403
  (let [ente (random-uuid)
        repo (fake-repo-pauta (fn [_ id] (sessao-canonica (random-uuid) id)) nil [])
        r (pt/response-for (service-fn* #{} repo)
                           :get (str "/sessoes/" (random-uuid) "/pauta") :headers (com-bearer (token ente (random-uuid))))]
    (is (= 403 (:status r)) "sessao de ente alheio -> policy.check (pode-ver-sessao?) nega -> 403")))

(deftest pauta-da-sessao-id-malformado-400
  (let [ente (random-uuid)
        repo (fake-repo-pauta (fn [e i] (sessao-canonica e i)) nil [])
        r (pt/response-for (service-fn* #{} repo)
                           :get "/sessoes/nao-e-uuid/pauta" :headers (com-bearer (token ente (random-uuid))))]
    (is (= 400 (:status r)) "path-param :id malformado -> 400, nunca 500")))

;; ---------- POST /sessoes — write path (wire/in + adapters/in + authz grossa exige-papel) ----------

(defn- corpo-agendar []
  (json/write-value-as-string
   {:sessao-legislativa-id (str (random-uuid)) :tipo-sessao "ordinaria"
    :modalidade "presencial" :agendada-para "2026-07-01T14:00:00Z"}))

;; NOTA: o mock de `pt/response-for` le o content-type da chave CAPITALIZADA "Content-Type" (io.pedestal.test
;; getContentType -> [:headers "Content-Type"]); o Pedestal entao popula o header Ring lowercase. Em producao
;; (Jetty) o header vem do HTTP cru — o interceptor corpo-json le "content-type" (lowercase, Ring-spec) certo.
(defn- com-json [tok] {"authorization" (str "Bearer " tok) "Content-Type" "application/json"})

(deftest agendar-sessao-201
  (let [r (pt/response-for (service-fn #{"secretario"} (fn [_ _] nil))
                           :post "/sessoes" :headers (com-json (token (random-uuid) (random-uuid)))
                           :body (corpo-agendar))
        body (ler-json r)]
    (is (= 201 (:status r)) "POST /sessoes com papel secretario + corpo valido -> 201")
    (is (= 7 (:numero-sequencial body)) "o recibo carrega o numero gapless do Repo")
    (is (string? (:id body)) "o recibo carrega o id criado (string)")))

(deftest agendar-sessao-sem-papel-403
  (let [r (pt/response-for (service-fn #{"vereador"} (fn [_ _] nil))
                           :post "/sessoes" :headers (com-json (token (random-uuid) (random-uuid)))
                           :body (corpo-agendar))]
    (is (= 403 (:status r)) "ator sem papel 'secretario' -> authz grossa nega -> 403")))

(deftest agendar-sessao-corpo-invalido-400
  (let [corpo (json/write-value-as-string {:modalidade "presencial"})  ; sem tipo-sessao nem sessao-legislativa-id
        r (pt/response-for (service-fn #{"secretario"} (fn [_ _] nil))
                           :post "/sessoes" :headers (com-json (token (random-uuid) (random-uuid)))
                           :body corpo)]
    (is (= 400 (:status r)) "corpo que nao casa o wire/in (AgendarSessao) -> 400 (validacao na borda)")))
