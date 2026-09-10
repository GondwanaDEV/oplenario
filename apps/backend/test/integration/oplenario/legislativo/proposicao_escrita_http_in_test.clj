(ns oplenario.legislativo.proposicao-escrita-http-in-test
  "Onda B Slice 2 — as 3 rotas novas de escrita: POST criar, GET detalhe, PATCH editar. DB-free (Repo FAKE)."
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

(defn- detalhe-canonico [ente id]
  {:id id :ente-id ente :tipo "projeto_lei" :ano 2026 :sequencial 1
   :urn-lex "urn:lex:x" :ementa "X" :estado "protocolada" :aprovada false :lock-version 0
   :atualizado-em (java.time.Instant/parse "2026-01-01T00:00:00Z")})

(defn- fake-repo-legislativo [{:keys [protocolar editar detalhe]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-leg/RepoLegislativo
    (protocolar! [_ _ente-id p] (protocolar p))
    (editar-proposicao! [_ _ente-id m] (editar m))
    (buscar-proposicao-detalhe [_ _ente-id id] (detalhe id))))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id] {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- fake-repo-cadastros
  "`uf-e-municipio` (exercido por `resolver-municipio`, sempre) + `buscar-vereador` (exercido por
  `vereador-vinculado?` — fix da review, achados I-1/M-1 — SO' quando o corpo manda `autor-id`;
  `vereadores-vinculados` e' o conjunto de ids que 'existem' neste ente-fake, default vazio)."
  ([uf municipio-nome] (fake-repo-cadastros uf municipio-nome #{}))
  ([uf municipio-nome vereadores-vinculados]
   #_{:clj-kondo/ignore [:missing-protocol-method]}
   (reify repo-cadastros-comp/RepoCadastros
     (uf-e-municipio [_ _ente-id] {:uf uf :municipio-nome municipio-nome})
     (buscar-vereador [_ _ente-id id] (when (contains? vereadores-vinculados id) {:id id})))))

(defn- service-fn
  "`repo-c` (repo-cadastros) e' opcional — so' e' de fato chamado (via `resolver-municipio`) quando a rota
  POST criar-proposicao roda; os outros testes (GET/PATCH/400/403) nunca alcancam esse ponto, entao nil
  basta (a closure `resolver-municipio` no host so' invoca o Repo quando CHAMADA, nao na construcao)."
  ([papeis repo-l] (service-fn papeis repo-l nil))
  ([papeis repo-l repo-c]
   (-> (http/servico (config/carregar)
                     (rotas/montar {:idp (idp-dev/idp-dev)
                                    :repo-identidade (fake-repo-identidade papeis)
                                    :repo-legislativo repo-l
                                    :repo-cadastros repo-c})
                     it/globais)
       ph/create-server ::ph/service-fn)))

(defn- token [ente-id ident-id] (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))
(defn- com-bearer
  "`Content-Type` (capitalizado) — o mock de `io.pedestal.test` (getContentType) le' a chave EXATA
  `Content-Type` do mapa `:headers`, case-sensitive (mesmo precedente de votacao-http-in-test/com-json);
  minuscula silenciosamente vira corpo vazio (`it/corpo-json` nao dispara -> :json-params nil -> 400)."
  [tok] {"authorization" (str "Bearer " tok) "Content-Type" "application/json"})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(deftest criar-proposicao-201
  (let [ente (random-uuid) id (random-uuid)
        repo (fake-repo-legislativo {:protocolar (fn [_p] {:id id :sequencial 1 :urn-lex "urn:x"})
                                      :detalhe (fn [_id] {:proposicao (detalhe-canonico ente id) :texto nil})})
        repo-c (fake-repo-cadastros "CE" "Fortaleza")
        r (pt/response-for (service-fn #{"secretario"} repo repo-c)
                           :post "/legislativo/proposicoes"
                           :headers (com-bearer (token ente (random-uuid)))
                           :body (json/write-value-as-string {:tipo "projeto_lei" :ano 2026 :ementa "X"}))]
    (is (= 201 (:status r)))
    (is (= "protocolada" (:estado (ler-json r))))))

(deftest criar-proposicao-corpo-invalido-400
  (let [repo (fake-repo-legislativo {})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post "/legislativo/proposicoes"
                           :headers (com-bearer (token (random-uuid) (random-uuid)))
                           :body (json/write-value-as-string {:tipo "lixo"}))]
    (is (= 400 (:status r)))))

(deftest criar-proposicao-sem-papel-403
  (let [repo (fake-repo-legislativo {})
        r (pt/response-for (service-fn #{"vereador"} repo)
                           :post "/legislativo/proposicoes"
                           :headers (com-bearer (token (random-uuid) (random-uuid)))
                           :body (json/write-value-as-string {:tipo "projeto_lei" :ano 2026 :ementa "X"}))]
    (is (= 403 (:status r)))))

;; ---------- fix da review (achados I-1 + M-1): autor-id ponta-a-ponta pela rota real ----------

(deftest criar-proposicao-autor-id-sem-vinculo-nesta-casa-400
  ;; I-1: autor-id que NAO bate com nenhum cadastro de vereador NESTE ente (via `buscar-vereador` do
  ;; repo-cadastros injetado) e' rejeitado ANTES de chegar ao Repo/protocolar! — fake-repo-legislativo sem
  ;; :protocolar estoura se o controller chamar mesmo assim (mesmo racional de editar-proposicao-inexistente-404).
  (let [ente (random-uuid)
        repo (fake-repo-legislativo {})
        repo-c (fake-repo-cadastros "CE" "Fortaleza")
        r (pt/response-for (service-fn #{"secretario"} repo repo-c)
                           :post "/legislativo/proposicoes"
                           :headers (com-bearer (token ente (random-uuid)))
                           :body (json/write-value-as-string
                                   {:tipo "projeto_lei" :ano 2026 :ementa "X"
                                    :autor-tipo "vereador" :autor-id (str (random-uuid))}))]
    (is (= 400 (:status r)))))

(deftest criar-proposicao-autor-id-com-autor-tipo-nao-vereador-400
  ;; M-1: autor-id presente com autor-tipo != vereador e' incoerente, mesmo que o id exista no cadastro.
  (let [ente (random-uuid) vid (random-uuid)
        repo (fake-repo-legislativo {})
        repo-c (fake-repo-cadastros "CE" "Fortaleza" #{vid})
        r (pt/response-for (service-fn #{"secretario"} repo repo-c)
                           :post "/legislativo/proposicoes"
                           :headers (com-bearer (token ente (random-uuid)))
                           :body (json/write-value-as-string
                                   {:tipo "projeto_lei" :ano 2026 :ementa "X"
                                    :autor-tipo "executivo" :autor-id (str vid)}))]
    (is (= 400 (:status r)))))

(deftest criar-proposicao-autor-id-vereador-vinculado-201
  ;; caminho feliz: autor-id de um vereador que EXISTE neste ente (fake) -> chega ao Repo, protocola.
  (let [ente (random-uuid) id (random-uuid) vid (random-uuid)
        repo (fake-repo-legislativo {:protocolar (fn [_p] {:id id :sequencial 1 :urn-lex "urn:x"})
                                      :detalhe (fn [_id] {:proposicao (detalhe-canonico ente id) :texto nil})})
        repo-c (fake-repo-cadastros "CE" "Fortaleza" #{vid})
        r (pt/response-for (service-fn #{"secretario"} repo repo-c)
                           :post "/legislativo/proposicoes"
                           :headers (com-bearer (token ente (random-uuid)))
                           :body (json/write-value-as-string
                                   {:tipo "projeto_lei" :ano 2026 :ementa "X"
                                    :autor-tipo "vereador" :autor-id (str vid) :autor-texto "Fulano de Tal"}))]
    (is (= 201 (:status r)))))

(deftest detalhe-proposicao-200
  (let [ente (random-uuid) id (random-uuid)
        repo (fake-repo-legislativo {:detalhe (fn [_id] {:proposicao (detalhe-canonico ente id) :texto nil})})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :get (str "/legislativo/proposicoes/" id)
                           :headers (com-bearer (token ente (random-uuid))))]
    (is (= 200 (:status r)))))

(deftest detalhe-proposicao-carrega-aprovada
  ;; Fatia 2 (guarda-autografo-votacao): o campo que o FE gateia botao atravessa a borda HTTP ate' o
  ;; corpo — nao so' o schema Malli em memoria.
  (let [ente (random-uuid) id (random-uuid)
        repo (fake-repo-legislativo
              {:detalhe (fn [_id] {:proposicao (assoc (detalhe-canonico ente id) :aprovada true) :texto nil})})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :get (str "/legislativo/proposicoes/" id)
                           :headers (com-bearer (token ente (random-uuid))))]
    (is (= 200 (:status r)))
    (is (true? (:aprovada (ler-json r))))))

(deftest detalhe-proposicao-inexistente-404
  (let [repo (fake-repo-legislativo {:detalhe (fn [_id] {:proposicao nil :texto nil})})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :get (str "/legislativo/proposicoes/" (random-uuid))
                           :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 404 (:status r)))))

(deftest editar-proposicao-200
  (let [ente (random-uuid) id (random-uuid)
        repo (fake-repo-legislativo {:editar (fn [m] {:id (:id m)})
                                      :detalhe (fn [_id] {:proposicao (detalhe-canonico ente id) :texto nil})})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :patch (str "/legislativo/proposicoes/" id)
                           :headers (com-bearer (token ente (random-uuid)))
                           :body (json/write-value-as-string {:lock-version 0 :ementa "Y"}))]
    (is (= 200 (:status r)))))

(deftest editar-proposicao-estado-terminal-400
  ;; Bug 2 (review ecc clojure+database, task 12): `db/proposicao.clj`'s `editar!` lanca "estado terminal"
  ;; SEM :tipo -> so' :validacao/invalido mapeia p/ 400 no interceptor global `erro`; sem a tag cai no
  ;; fallback -> 500 opaco (e polui log de servidor) num caminho que e' fluxo normal (CAS de PATCH). Mirrors
  ;; o shape EXATO que `editar!` produz apos o fix (`:tipo :validacao/invalido` adicionado).
  (let [ente (random-uuid) id (random-uuid)
        repo (fake-repo-legislativo
              {:editar (fn [_m] (throw (ex-info "editar!: proposicao em estado terminal nao edita"
                                                 {:tipo :validacao/invalido :id id :estado "sancionada"})))
               :detalhe (fn [_id] {:proposicao (detalhe-canonico ente id) :texto nil})})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :patch (str "/legislativo/proposicoes/" id)
                           :headers (com-bearer (token ente (random-uuid)))
                           :body (json/write-value-as-string {:lock-version 0 :ementa "Y"}))]
    (is (= 400 (:status r)))))

(deftest editar-proposicao-conflito-lock-version-400
  ;; Bug 2, 2a metade: mesma lacuna no throw de "conflito de lock_version" (o desfecho PRIMARIO/esperado de
  ;; um PATCH CAS sob concorrencia — dois servidores editando ao mesmo tempo, ou resubmit de cliente
  ;; obsoleto — nunca deveria ser 500).
  (let [ente (random-uuid) id (random-uuid)
        repo (fake-repo-legislativo
              {:editar (fn [_m] (throw (ex-info "editar!: conflito de lock_version ou proposicao inexistente"
                                                 {:tipo :validacao/invalido :id id :lock-version 0})))
               :detalhe (fn [_id] {:proposicao (detalhe-canonico ente id) :texto nil})})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :patch (str "/legislativo/proposicoes/" id)
                           :headers (com-bearer (token ente (random-uuid)))
                           :body (json/write-value-as-string {:lock-version 0 :ementa "Y"}))]
    (is (= 400 (:status r)))))

(deftest editar-proposicao-inexistente-404
  ;; Regressao: o handler deve fazer o pre-check via `buscar-proposicao-ficha` ANTES de chamar
  ;; `editar-proposicao` — sem `:editar` no fake-repo, se o handler chamar `editar-proposicao!` mesmo
  ;; assim, `editar` (nil) sera invocada como fn e o teste estoura (sinal de que a ordem do pre-check
  ;; esta errada), nunca produzindo silenciosamente um 500 mascarado de "passou".
  (let [repo (fake-repo-legislativo {:detalhe (fn [_id] {:proposicao nil :texto nil})})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :patch (str "/legislativo/proposicoes/" (random-uuid))
                           :headers (com-bearer (token (random-uuid) (random-uuid)))
                           :body (json/write-value-as-string {:lock-version 0 :ementa "Y"}))]
    (is (= 404 (:status r)))))

;; ---------- fix da review (achados I-1 + M-1) no PATCH ----------

(deftest editar-proposicao-autor-id-sem-autor-tipo-vereador-na-mesma-escrita-400
  ;; M-1: PATCH que muda autor-id SEM reafirmar autor-tipo=vereador na MESMA chamada e' incoerente (decisao
  ;; deliberada de nao ler a linha anterior — ver docstring de controllers/validar-autor!).
  (let [ente (random-uuid) id (random-uuid)
        repo (fake-repo-legislativo {:detalhe (fn [_id] {:proposicao (detalhe-canonico ente id) :texto nil})})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :patch (str "/legislativo/proposicoes/" id)
                           :headers (com-bearer (token ente (random-uuid)))
                           :body (json/write-value-as-string {:lock-version 0 :autor-id (str (random-uuid))}))]
    (is (= 400 (:status r)))))

(deftest editar-proposicao-autor-id-sem-vinculo-nesta-casa-400
  ;; I-1 no PATCH: autor-tipo=vereador + autor-id que NAO bate com nenhum cadastro deste ente -> 400.
  (let [ente (random-uuid) id (random-uuid)
        repo (fake-repo-legislativo {:detalhe (fn [_id] {:proposicao (detalhe-canonico ente id) :texto nil})})
        repo-c (fake-repo-cadastros "CE" "Fortaleza")
        r (pt/response-for (service-fn #{"secretario"} repo repo-c)
                           :patch (str "/legislativo/proposicoes/" id)
                           :headers (com-bearer (token ente (random-uuid)))
                           :body (json/write-value-as-string
                                   {:lock-version 0 :autor-tipo "vereador" :autor-id (str (random-uuid))}))]
    (is (= 400 (:status r)))))

(deftest editar-proposicao-autor-id-vereador-vinculado-200
  (let [ente (random-uuid) id (random-uuid) vid (random-uuid)
        repo (fake-repo-legislativo {:editar (fn [m] {:id (:id m)})
                                      :detalhe (fn [_id] {:proposicao (detalhe-canonico ente id) :texto nil})})
        repo-c (fake-repo-cadastros "CE" "Fortaleza" #{vid})
        r (pt/response-for (service-fn #{"secretario"} repo repo-c)
                           :patch (str "/legislativo/proposicoes/" id)
                           :headers (com-bearer (token ente (random-uuid)))
                           :body (json/write-value-as-string
                                   {:lock-version 0 :autor-tipo "vereador" :autor-id (str vid) :autor-texto "Fulano de Tal"}))]
    (is (= 200 (:status r)))))
