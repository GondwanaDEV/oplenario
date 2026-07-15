(ns oplenario.cadastros.vereador-http-in-test
  "Task 5 (borda HTTP do cadastros — PRIMEIRA borda do modulo) — a vertical de leitura GET
  /cadastros/vereadores (lista) e GET /cadastros/vereadores/:id (ficha): prova a silhueta de borda
  end-to-end (controller -> repo -> adapters/out -> wire/out) + a authz grossa (papel 'secretario') + 401.
  DB-free: RepoCadastros FAKE (reify, so' os 2 metodos exercidos) + idp-dev real (mesmo precedente de
  proposicao-http-in-test/painel-http-in-test)."
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.cadastros.components.repositorio :as repo-cad]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.rotas :as rotas]))

(defn- linha-canonica [id]
  {:id id :nome "Helena Matos" :nome-parlamentar "Helena" :partido "PT"
   :estado-mandato "ativo" :cargo-mesa "presidente"})

(defn- ficha-canonica [id]
  {:vereador {:id id :nome "Helena Matos" :nome-parlamentar "Helena"}
   :mandato {:partido "PT" :estado "ativo" :natureza "titular"
             :vigencia-inicio (java.time.LocalDate/of 2025 1 1)
             :legislatura-id nil}
   :legislatura nil
   :comissoes []})

(defn- fake-repo-cadastros
  "RepoCadastros fake: `listar-vereadores` devolve `linhas`; `ficha-vereador` devolve `ficha` (ou nil, p/
  simular 404). `ligar` (Task 9, opcional): numero (update-count de `ligar-identidade!`, default 1 = sucesso)
  ou fn de 0 args (p/ simular o throw de :conflito/identidade-ja-vinculada — mesmo padrao de `mandato`/
  `licenca` em `fake-repo-escrita` abaixo). Impl parcial proposital (so' os metodos exercidos pela borda
  desta task)."
  ([linhas ficha] (fake-repo-cadastros linhas ficha nil))
  ([linhas ficha ligar]
   #_{:clj-kondo/ignore [:missing-protocol-method]}
   (reify repo-cad/RepoCadastros
     (listar-vereadores [_ _ente-id _data] linhas)
     (ficha-vereador [_ _ente-id _id _data] ficha)
     (ligar-identidade! [_ _ente-id _id _identidade-id] (if (fn? ligar) (ligar) (or ligar 1))))))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- service-fn
  "`opts` (Task 9, variadico): overrides adicionais p/ `rotas/montar` — ex. `:identidade-existe?` (guard de
  servico da rota /identidade, mesma forma dos demais overrides injetaveis do host)."
  [papeis repo-c & {:as opts}]
  (-> (http/servico (config/carregar)
                    (rotas/montar (merge {:idp (idp-dev/idp-dev)
                                          :repo-identidade (fake-repo-identidade papeis)
                                          :repo-cadastros repo-c}
                                         opts))
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id]
  (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))

(defn- com-bearer [tok] {"authorization" (str "Bearer " tok)})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

;; ---------- GET /cadastros/vereadores ----------

(deftest listar-vereadores-200
  (let [ente (random-uuid) id (random-uuid)
        r (pt/response-for (service-fn #{"secretario"} (fake-repo-cadastros [(linha-canonica id)] nil))
                           :get "/cadastros/vereadores" :headers (com-bearer (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)) "GET /cadastros/vereadores com papel secretario -> 200")
    (is (= 1 (count (:vereadores body))) "o envelope {:vereadores [...]}")
    (let [v (first (:vereadores body))]
      (is (= "Helena Matos" (:nome v)))
      (is (string? (:id v)) "id como string"))))

(deftest listar-vereadores-vazio-200
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-cadastros [] nil))
                           :get "/cadastros/vereadores" :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 200 (:status r)))
    (is (= [] (:vereadores (ler-json r))))))

(deftest listar-vereadores-sem-papel-403
  (let [r (pt/response-for (service-fn #{"vereador"} (fake-repo-cadastros [] nil))
                           :get "/cadastros/vereadores" :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 403 (:status r)) "ator sem papel 'secretario' -> authz grossa nega -> 403")))

(deftest listar-vereadores-sem-token-401
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-cadastros [] nil))
                           :get "/cadastros/vereadores")]
    (is (= 401 (:status r)) "rota de modulo herda a cadeia de auth: sem token -> 401 (fail-closed)")))

;; ---------- GET /cadastros/vereadores/:id ----------

(deftest ficha-vereador-200
  (let [ente (random-uuid) id (random-uuid)
        r (pt/response-for (service-fn #{"secretario"} (fake-repo-cadastros [] (ficha-canonica id)))
                           :get (str "/cadastros/vereadores/" id)
                           :headers (com-bearer (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= "Helena Matos" (:nome body)))
    (is (= [] (:comissoes body)))))

(deftest ficha-vereador-inexistente-404
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-cadastros [] nil))
                           :get (str "/cadastros/vereadores/" (random-uuid))
                           :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 404 (:status r)))
    (is (= "vereador nao encontrado" (:erro (ler-json r))))))

(deftest ficha-vereador-id-invalido-404
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-cadastros [] nil))
                           :get "/cadastros/vereadores/nao-e-um-uuid"
                           :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 404 (:status r)) "path param nao-UUID -> 404, nunca 500")))

(deftest ficha-vereador-sem-papel-403
  (let [r (pt/response-for (service-fn #{"vereador"} (fake-repo-cadastros [] (ficha-canonica (random-uuid))))
                           :get (str "/cadastros/vereadores/" (random-uuid))
                           :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 403 (:status r)))))

;; ---------- Task 6: escritas + legislatura-vigente ----------

(defn- fake-repo-escrita
  "RepoCadastros fake com as escritas + legislatura-vigente. Cada fn devolve o que o teste precisa; use nil
  p/ 404 e (throw (ex-info ... {:tipo :conflito/...})) p/ 409."
  [{:keys [criar editar mandato licenca leg]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cad/RepoCadastros
    (criar-vereador!    [_ _ _]     (or criar {:next.jdbc/update-count 1}))
    (atualizar-vereador! [_ _ _ _]  (if (some? editar) editar 1))
    (registrar-mandato! [_ _ m]     (if (fn? mandato) (mandato m) mandato))
    (registrar-licenca! [_ _ _ l _] (if (fn? licenca) (licenca l) licenca))
    (legislatura-vigente [_ _]      leg)))

(defn- post [service tok path body]
  ;; "Content-Type" CAPITALIZADO — o mock de `io.pedestal.test` (getContentType) le' a chave EXATA
  ;; `Content-Type`, case-sensitive (mesmo precedente de proposicao-escrita-http-in-test/com-bearer);
  ;; minuscula silenciosamente vira corpo vazio (`it/corpo-json` nao dispara -> :json-params nil -> 400).
  (pt/response-for service :post path
    :headers (assoc (com-bearer tok) "Content-Type" "application/json")
    :body (json/write-value-as-string body)))
(defn- patch* [service tok path body]
  (pt/response-for service :patch path
    :headers (assoc (com-bearer tok) "Content-Type" "application/json")
    :body (json/write-value-as-string body)))

(deftest criar-vereador-201
  (let [ente (random-uuid)
        r (post (service-fn #{"secretario"} (fake-repo-escrita {}))
                (token ente (random-uuid)) "/cadastros/vereadores" {:nome "Nova Vereadora"})]
    (is (= 201 (:status r)))
    (is (string? (:id (ler-json r))) "devolve {:id}")))

(deftest criar-vereador-corpo-invalido-400
  (let [r (post (service-fn #{"secretario"} (fake-repo-escrita {}))
                (token (random-uuid) (random-uuid)) "/cadastros/vereadores" {:nome-parlamentar "sem nome"})]
    (is (= 400 (:status r)) "sem :nome -> adapters/in lanca :validacao/invalido -> 400")))

(deftest criar-vereador-sem-papel-403
  (let [r (post (service-fn #{"vereador"} (fake-repo-escrita {}))
                (token (random-uuid) (random-uuid)) "/cadastros/vereadores" {:nome "X"})]
    (is (= 403 (:status r)))))

(deftest editar-vereador-200-e-404
  (let [tok (token (random-uuid) (random-uuid))]
    (is (= 200 (:status (patch* (service-fn #{"secretario"} (fake-repo-escrita {:editar 1}))
                                tok (str "/cadastros/vereadores/" (random-uuid)) {:nome "Novo"}))))
    (is (= 404 (:status (patch* (service-fn #{"secretario"} (fake-repo-escrita {:editar 0}))
                                tok (str "/cadastros/vereadores/" (random-uuid)) {:nome "Novo"}))))
    (is (= 404 (:status (patch* (service-fn #{"secretario"} (fake-repo-escrita {:editar 1}))
                                tok "/cadastros/vereadores/nao-uuid" {:nome "Novo"})))
        ":id malformado -> 404, nunca 500")))

(deftest registrar-mandato-201-404-409
  (let [tok (token (random-uuid) (random-uuid)) ver (random-uuid)
        corpo {:legislatura-id (str (random-uuid)) :natureza "titular" :vigencia-inicio "2025-01-01"}
        sobrepoe (fn [_] (throw (ex-info "x" {:tipo :conflito/mandato-sobreposto})))]
    (is (= 201 (:status (post (service-fn #{"secretario"} (fake-repo-escrita {:mandato (fn [m] {:id (:id m)})}))
                              tok (str "/cadastros/vereadores/" ver "/mandatos") corpo))))
    (is (= 404 (:status (post (service-fn #{"secretario"} (fake-repo-escrita {:mandato nil}))
                              tok (str "/cadastros/vereadores/" ver "/mandatos") corpo))))
    (is (= 409 (:status (post (service-fn #{"secretario"} (fake-repo-escrita {:mandato sobrepoe}))
                              tok (str "/cadastros/vereadores/" ver "/mandatos") corpo))))))

(deftest registrar-licenca-201-404-409
  (let [tok (token (random-uuid) (random-uuid)) ver (random-uuid)
        corpo {:inicio "2026-03-01"}
        sem-vigente (fn [_] (throw (ex-info "x" {:tipo :conflito/sem-mandato-vigente})))]
    (is (= 201 (:status (post (service-fn #{"secretario"} (fake-repo-escrita {:licenca (fn [l] {:id (:id l)})}))
                              tok (str "/cadastros/vereadores/" ver "/licencas") corpo))))
    (is (= 404 (:status (post (service-fn #{"secretario"} (fake-repo-escrita {:licenca nil}))
                              tok (str "/cadastros/vereadores/" ver "/licencas") corpo))))
    (is (= 409 (:status (post (service-fn #{"secretario"} (fake-repo-escrita {:licenca sem-vigente}))
                              tok (str "/cadastros/vereadores/" ver "/licencas") corpo))))))

;; ---------- Task 9: PATCH /cadastros/vereadores/:id/identidade ----------

(deftest ligar-identidade-200
  (let [ente (random-uuid) vid (random-uuid) ident (random-uuid)
        r (patch* (service-fn #{"admin_ente"} (fake-repo-cadastros [] nil)
                              :identidade-existe? (constantly true))
                  (token ente (random-uuid)) (str "/cadastros/vereadores/" vid "/identidade")
                  {:identidade-id (str ident)})]
    (is (= 200 (:status r)))
    (is (= (str ident) (:identidade-id (ler-json r))) "devolve o id ligado")))

(deftest ligar-identidade-sem-admin-ente-403
  (let [r (patch* (service-fn #{"secretario"} (fake-repo-cadastros [] nil)
                              :identidade-existe? (constantly true))
                  (token (random-uuid) (random-uuid)) (str "/cadastros/vereadores/" (random-uuid) "/identidade")
                  {:identidade-id (str (random-uuid))})]
    (is (= 403 (:status r)) "ligar identidade e' parte de conceder acesso — nao e' do secretario")))

(deftest ligar-identidade-guard-inexistente-404
  (let [r (patch* (service-fn #{"admin_ente"} (fake-repo-cadastros [] nil)
                              :identidade-existe? (constantly false))
                  (token (random-uuid) (random-uuid)) (str "/cadastros/vereadores/" (random-uuid) "/identidade")
                  {:identidade-id (str (random-uuid))})]
    (is (= 404 (:status r))
        "guard de servico: sem FK cross-schema, a existencia da identidade e' checada por seam do host")
    (is (= "vereador ou identidade nao encontrada" (:erro (ler-json r)))
        "mensagem generica (review IMPORTANT-2)")))

(deftest ligar-identidade-id-invalido-e-identidade-inexistente-sao-indistinguiveis-404
  ;; Review Task 9 IMPORTANT-2: `identidade-existe?` resolve pra `identidade-por-id`, que e' SUPRATENANT
  ;; (sem escopo de ente_id) — um `admin_ente` de QUALQUER Casa poderia usar o TEXTO do erro como oraculo
  ;; pra descobrir se um `identidade-id` chutado existe em algum lugar do sistema. Prova a propriedade que
  ;; importa: as duas causas de 404 (path :id malformado vs. identidade que o guard nao reconhece) tem
  ;; a MESMA resposta byte-a-byte, entao a resposta nao vaza qual das duas faltou.
  (let [ident (str (random-uuid))
        r-id-invalido (patch* (service-fn #{"admin_ente"} (fake-repo-cadastros [] nil)
                                          :identidade-existe? (constantly true))
                              (token (random-uuid) (random-uuid)) "/cadastros/vereadores/nao-uuid/identidade"
                              {:identidade-id ident})
        r-identidade-ausente (patch* (service-fn #{"admin_ente"} (fake-repo-cadastros [] nil)
                                                 :identidade-existe? (constantly false))
                                     (token (random-uuid) (random-uuid))
                                     (str "/cadastros/vereadores/" (random-uuid) "/identidade")
                                     {:identidade-id ident})]
    (is (= 404 (:status r-id-invalido) (:status r-identidade-ausente)))
    (is (= (:body r-id-invalido) (:body r-identidade-ausente))
        "mesmo corpo de resposta pras duas causas -> nenhuma delas e' distinguivel pelo cliente")))

(deftest ligar-identidade-vereador-inexistente-404
  (let [r (patch* (service-fn #{"admin_ente"} (fake-repo-cadastros [] nil 0) ; update-count 0
                              :identidade-existe? (constantly true))
                  (token (random-uuid) (random-uuid)) (str "/cadastros/vereadores/" (random-uuid) "/identidade")
                  {:identidade-id (str (random-uuid))})]
    (is (= 404 (:status r)) "vereador inexistente/de-outro-tenant -> update-count 0 -> 404")))

(deftest ligar-identidade-conflito-409
  (let [conflito (fn [] (throw (ex-info "x" {:tipo :conflito/identidade-ja-vinculada})))
        r (patch* (service-fn #{"admin_ente"} (fake-repo-cadastros [] nil conflito)
                              :identidade-existe? (constantly true))
                  (token (random-uuid) (random-uuid)) (str "/cadastros/vereadores/" (random-uuid) "/identidade")
                  {:identidade-id (str (random-uuid))})]
    (is (= 409 (:status r))
        "identidade ja ligada a OUTRO vereador nesta Casa -> 409 LOCAL, nunca 500")))

(deftest ligar-identidade-id-invalido-404
  (let [r (patch* (service-fn #{"admin_ente"} (fake-repo-cadastros [] nil)
                              :identidade-existe? (constantly true))
                  (token (random-uuid) (random-uuid)) "/cadastros/vereadores/nao-uuid/identidade"
                  {:identidade-id (str (random-uuid))})]
    (is (= 404 (:status r)) ":id do path nao-UUID -> 404, nunca 500")))

(deftest ligar-identidade-corpo-invalido-400
  (let [r (patch* (service-fn #{"admin_ente"} (fake-repo-cadastros [] nil)
                              :identidade-existe? (constantly true))
                  (token (random-uuid) (random-uuid)) (str "/cadastros/vereadores/" (random-uuid) "/identidade")
                  {:identidade-id "nao-e-um-uuid"})]
    (is (= 400 (:status r)) "identidade-id que nao parseia como UUID -> 400 (adapters/in)")))

(deftest legislatura-vigente-200-e-404
  (let [tok (token (random-uuid) (random-uuid))
        leg {:id (random-uuid) :numero 19 :ano-inicio 2025 :ano-fim 2028 :vigente true}]
    (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-escrita {:leg leg}))
                             :get "/cadastros/legislatura-vigente" :headers (com-bearer tok))]
      (is (= 200 (:status r)))
      (is (= 19 (:numero (ler-json r)))))
    (is (= 404 (:status (pt/response-for (service-fn #{"secretario"} (fake-repo-escrita {:leg nil}))
                                         :get "/cadastros/legislatura-vigente" :headers (com-bearer tok)))))))
