(ns oplenario.legislativo.meu-painel-http-in-test
  "Onda C1 — a borda HTTP /meu do vereador: GET /meu/painel + POST /meu/ciencias. DB-free (Repo FAKE,
  reify parcial, mesmo racional de pos-aprovacao-http-in-test): o Repo real (db/meu_painel,
  RepoLegislativo/meu-painel|acusar-ciencia!) ja' tem cobertura de integracao real em meu_painel_test.clj.
  Foco AQUI e' a borda: gate de papel 'vereador', anti-forja (vereador-id sempre resolvido do ator, nunca
  do corpo), e o contrato 200-vazio (nunca 500) quando o ator nao tem cadastro vinculado."
  (:require [clojure.test :refer [deftest is]]
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
            [oplenario.rotas :as rotas]))

(defn- fake-repo-legislativo
  "RepoLegislativo fake (parcial proposital — so' meu-painel/acusar-ciencia!). A AUSENCIA de uma chave e'
  PROPOSITAL: se o handler chamar o metodo fora de ordem, a chamada nil estoura — sinaliza a regressao em
  vez de passar silenciosamente."
  [{:keys [meu-painel acusar-ciencia!]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-leg/RepoLegislativo
    (meu-painel [_ ente-id vereador-id] (meu-painel ente-id vereador-id))
    (acusar-ciencia! [_ ente-id m] (acusar-ciencia! ente-id m))))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id] {:vinculo-ativo {:id (random-uuid) :tipo "vereador"} :papeis papeis})))

(defn- fake-repo-cadastros
  "So' o metodo exercido por `resolver-vereador` (host, rotas.clj) — `vereador-por-identidade`. O real
  devolve a LINHA do vereador ({:id ...} ou nil); `resolver-vereador` (rotas.clj) extrai so' o `:id` —
  aqui `resolver` (a fn de teste) devolve DIRETO o vereador-id (uuid) ou nil, e este fake embrulha em
  {:id ...} p/ casar o contrato real."
  [resolver]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cadastros-comp/RepoCadastros
    (vereador-por-identidade [_ ente-id identidade-id]
      (when-let [v (resolver ente-id identidade-id)] {:id v}))))

(defn- service-fn [papeis repo-l resolver-vereador]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-legislativo repo-l
                                   :repo-cadastros (fake-repo-cadastros resolver-vereador)})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id] (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))
(defn- com-bearer [tok] {"authorization" (str "Bearer " tok) "Content-Type" "application/json"})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(defn- painel-fake []
  {:proposicoes [] :pareceres [] :ciencias []})

;; ========================= GET /meu/painel =========================

(deftest meu-painel-200-so-o-que-e-do-vereador-resolvido
  (let [ente (random-uuid) identidade (random-uuid) vereador (random-uuid)
        chamado-com (atom nil)
        repo (fake-repo-legislativo
              {:meu-painel (fn [e v] (reset! chamado-com {:ente e :vereador v}) (painel-fake))})
        r (pt/response-for (service-fn #{"vereador"} repo (fn [e i] (when (and (= e ente) (= i identidade)) vereador)))
                           :get "/meu/painel" :headers (com-bearer (token ente identidade)))]
    (is (= 200 (:status r)))
    (is (= {:ente ente :vereador vereador} @chamado-com)
        "o vereador-id chamado no Repo e' SEMPRE o resolvido do ator (identidade->vereador), nunca outro")))

(deftest meu-painel-vazio-quando-resolver-vereador-nil-nunca-500
  ;; ator com papel 'vereador' mas sem cadastro vinculado neste ente -> painel vazio (200), o Repo NEM E'
  ;; CHAMADO (fake sem :meu-painel: se o controller chamasse mesmo assim, estouraria em vez de 200 vazio).
  (let [r (pt/response-for (service-fn #{"vereador"} (fake-repo-legislativo {}) (fn [_e _i] nil))
                           :get "/meu/painel" :headers (com-bearer (token (random-uuid) (random-uuid))))
        corpo (ler-json r)]
    (is (= 200 (:status r)))
    (is (= {:proposicoes [] :pareceres [] :ciencias []} corpo))))

(deftest meu-painel-sem-papel-vereador-403
  (doseq [papeis [#{"secretario"} #{"cidadao"}]]
    (let [r (pt/response-for (service-fn papeis (fake-repo-legislativo {}) (fn [_e _i] (random-uuid)))
                             :get "/meu/painel" :headers (com-bearer (token (random-uuid) (random-uuid))))]
      (is (= 403 (:status r)) (str "papeis=" papeis)))))

(deftest meu-painel-anti-forja-vereador-id-na-query-e-ignorado
  ;; mandar vereador-id na QUERY nao muda de quem e' o painel — o handler nem le query params p/ isso; o
  ;; unico vereador-id usado e' o resolvido do ator (`vereador`), nunca o `outro-vereador` da query.
  (let [ente (random-uuid) identidade (random-uuid) vereador (random-uuid) outro-vereador (random-uuid)
        chamado-com (atom nil)
        repo (fake-repo-legislativo
              {:meu-painel (fn [e v] (reset! chamado-com {:ente e :vereador v}) (painel-fake))})
        r (pt/response-for (service-fn #{"vereador"} repo (fn [e i] (when (and (= e ente) (= i identidade)) vereador)))
                           :get (str "/meu/painel?vereador-id=" outro-vereador)
                           :headers (com-bearer (token ente identidade)))]
    (is (= 200 (:status r)))
    (is (= vereador (:vereador @chamado-com)))
    (is (not= outro-vereador (:vereador @chamado-com)))))

;; ========================= POST /meu/ciencias =========================

(deftest acusar-ciencia-201-anti-forja-ignora-vereador-id-do-corpo
  (let [ente (random-uuid) identidade (random-uuid) vereador (random-uuid) evento (random-uuid)
        outro-vereador (random-uuid)
        recebido (atom nil)
        repo (fake-repo-legislativo
              {:acusar-ciencia! (fn [_e m] (reset! recebido m) {:id (random-uuid) :ciente-em (java.time.Instant/parse "2026-07-11T09:14:00Z")})})
        r (pt/response-for (service-fn #{"vereador"} repo (fn [e i] (when (and (= e ente) (= i identidade)) vereador)))
                           :post "/meu/ciencias" :headers (com-bearer (token ente identidade))
                           :body (json/write-value-as-string
                                   {:evento-ref (str evento) :tipo "parecer_publicado"
                                    :vereador-id (str outro-vereador)}))]
    (is (= 201 (:status r)))
    (is (= vereador (:vereador-id @recebido))
        "vereador-id no Repo e' SEMPRE o resolvido do ator, mesmo o corpo mandando outro (campo extra ignorado)")))

(deftest acusar-ciencia-reflete-no-proximo-get
  ;; 201 e o RECIBO carrega :id/:ciente-em; o proximo GET /meu/painel ja' NAO lista mais a ciencia acusada
  ;; (o Repo real garante isso via anti-join, aqui o fake so' PRECISA devolver um recibo bem-formado —
  ;; o "reflete" e' coberto de fato em meu_painel_test.clj/db_test; aqui confirmamos so' a FORMA do recibo
  ;; e que o mesmo service atende as duas chamadas sem 500).
  (let [ente (random-uuid) identidade (random-uuid) vereador (random-uuid) evento (random-uuid)
        acusado? (atom false)
        repo (fake-repo-legislativo
              {:acusar-ciencia! (fn [_e _m] (reset! acusado? true)
                                   {:id (random-uuid) :ciente-em (java.time.Instant/parse "2026-07-11T09:14:00Z")})
               :meu-painel (fn [_e _v] (if @acusado? (painel-fake) {:proposicoes [] :pareceres []
                                                                      :ciencias [{:parecer-id evento :proposicao-id (random-uuid)
                                                                                  :tipo "projeto_lei" :ano 2026 :sequencial 1
                                                                                  :urn-lex "urn:fixture" :ementa "X"}]}))})
        resolver (fn [e i] (when (and (= e ente) (= i identidade)) vereador))
        service (service-fn #{"vereador"} repo resolver)
        r-get-antes (pt/response-for service :get "/meu/painel" :headers (com-bearer (token ente identidade)))
        r-post (pt/response-for service
                                :post "/meu/ciencias" :headers (com-bearer (token ente identidade))
                                :body (json/write-value-as-string {:evento-ref (str evento) :tipo "parecer_publicado"}))
        corpo-post (ler-json r-post)
        r-get-depois (pt/response-for service :get "/meu/painel" :headers (com-bearer (token ente identidade)))
        corpo-depois (ler-json r-get-depois)]
    (is (= 200 (:status r-get-antes)))
    (is (= 1 (count (:ciencias (ler-json r-get-antes)))) "antes de acusar, a ciencia aparece pendente")
    (is (= 201 (:status r-post)))
    (is (some? (:id corpo-post)))
    (is (some? (:ciente-em corpo-post)))
    (is (= 200 (:status r-get-depois)))
    (is (= [] (:ciencias corpo-depois)) "depois de acusar, o proximo GET nao lista mais a ciencia")))

(deftest acusar-ciencia-sem-cadastro-404
  (let [r (pt/response-for (service-fn #{"vereador"} (fake-repo-legislativo {}) (fn [_e _i] nil))
                           :post "/meu/ciencias" :headers (com-bearer (token (random-uuid) (random-uuid)))
                           :body (json/write-value-as-string {:evento-ref (str (random-uuid)) :tipo "parecer_publicado"}))]
    (is (= 404 (:status r)))))

(deftest acusar-ciencia-corpo-invalido-400
  (let [r (pt/response-for (service-fn #{"vereador"} (fake-repo-legislativo {}) (fn [_e _i] (random-uuid)))
                           :post "/meu/ciencias" :headers (com-bearer (token (random-uuid) (random-uuid)))
                           :body (json/write-value-as-string {:evento-ref "nao-e-uuid" :tipo "parecer_publicado"}))]
    (is (= 400 (:status r)))))

(deftest acusar-ciencia-sem-papel-vereador-403
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-legislativo {}) (fn [_e _i] (random-uuid)))
                           :post "/meu/ciencias" :headers (com-bearer (token (random-uuid) (random-uuid)))
                           :body (json/write-value-as-string {:evento-ref (str (random-uuid)) :tipo "parecer_publicado"}))]
    (is (= 403 (:status r)))))
