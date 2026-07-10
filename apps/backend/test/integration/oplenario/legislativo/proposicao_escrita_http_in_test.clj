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
   :urn-lex "urn:lex:x" :ementa "X" :estado "protocolada" :lock-version 0
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
  "So' o metodo exercido por `criar-proposicao-handler` (`resolver-municipio`) — os demais nao sao chamados
  pela rota de escrita de proposicao (DB-free, mesmo racional de fake-repo-legislativo)."
  [uf municipio-nome]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cadastros-comp/RepoCadastros
    (uf-e-municipio [_ _ente-id] {:uf uf :municipio-nome municipio-nome})))

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

(deftest detalhe-proposicao-200
  (let [ente (random-uuid) id (random-uuid)
        repo (fake-repo-legislativo {:detalhe (fn [_id] {:proposicao (detalhe-canonico ente id) :texto nil})})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :get (str "/legislativo/proposicoes/" id)
                           :headers (com-bearer (token ente (random-uuid))))]
    (is (= 200 (:status r)))))

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
