(ns oplenario.legislativo.expediente-http-in-test
  "Onda B Slice 6 — a borda HTTP do Expediente (aba 'Gerar documento' + o Livro do Protocolo Geral): GET
  modelos, POST/GET/PATCH documento, POST .../protocolo (o CTA 'Protocolar e numerar'), GET protocolo-geral.
  DB-free (Repo FAKE, reify parcial) — mesmo racional de proposicao-escrita-http-in-test/votacao-http-in-test:
  os Repo reais (db/documento, db/documento-modelo, db/protocolo-geral, o `protocolar-documento!` composto) ja'
  tem cobertura de integracao real em documento_repo_test.clj/documento_db_test.clj."
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.rotas :as rotas]))

(defn- modelo-canonico [ente id]
  {:id id :ente-id ente :chave "oficio" :nome "Oficio [FIXTURE]" :tipo-documento "oficio"
   :corpo-template "Ao {{destinatario}}." :ativo true :lock-version 0})

(defn- documento-canonico
  [ente id & {:keys [estado protocolo-geral-id lock-version] :or {estado "rascunho" lock-version 0}}]
  {:id id :ente-id ente :modelo-id (random-uuid) :tipo-documento "oficio" :assunto "Convite [FIXTURE]"
   :corpo "Ao Prefeito." :estado estado :protocolo-geral-id protocolo-geral-id :lock-version lock-version
   :criado-em (java.time.Instant/parse "2026-01-01T00:00:00Z")})

(defn- protocolo-canonico [ente id documento-id]
  {:id id :ente-id ente :numero 1 :ano 2026 :objeto-tipo "documento" :objeto-id documento-id
   :sentido "expedido" :assunto "Convite [FIXTURE]"
   :protocolado-em (java.time.Instant/parse "2026-01-01T00:00:00Z")})

(defn- fake-repo-legislativo
  "RepoLegislativo fake (parcial proposital — so' os metodos da vertical de expediente). Cada aridade e' um
  closure de teste; a AUSENCIA de uma chave e' PROPOSITAL em varios testes (mesmo racional de
  editar-proposicao-inexistente-404): se o handler chamar o metodo fora de ordem, a chamada nil estoura, o
  que sinaliza a regressao em vez de passar silenciosamente."
  [{:keys [buscar-modelo listar-modelos-ativos gerar-documento! buscar-documento buscar-protocolo
           editar-documento! protocolar-documento! protocolos-do-ano]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-leg/RepoLegislativo
    (buscar-modelo [_ _ente-id id] (buscar-modelo id))
    (listar-modelos-ativos [_ _ente-id] (listar-modelos-ativos))
    (gerar-documento! [_ _ente-id m] (gerar-documento! m))
    (buscar-documento [_ _ente-id id] (buscar-documento id))
    (buscar-protocolo [_ _ente-id id] (buscar-protocolo id))
    (editar-documento! [_ _ente-id m] (editar-documento! m))
    (protocolar-documento! [_ _ente-id m] (protocolar-documento! m))
    (protocolos-do-ano [_ _ente-id ano] (protocolos-do-ano ano))))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id] {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- service-fn [papeis repo-l]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-legislativo repo-l})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id] (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))
(defn- com-bearer [tok] {"authorization" (str "Bearer " tok) "Content-Type" "application/json"})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

;; ========================= GET /legislativo/documento-modelos =========================

(deftest listar-modelos-documento-200
  (let [ente (random-uuid) mid (random-uuid)
        repo (fake-repo-legislativo {:listar-modelos-ativos (fn [] [(modelo-canonico ente mid)])})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :get "/legislativo/documento-modelos"
                           :headers (com-bearer (token ente (random-uuid))))]
    (is (= 200 (:status r)))
    (is (= 1 (count (:itens (ler-json r)))))
    (is (= "oficio" (:chave (first (:itens (ler-json r))))))))

(deftest listar-modelos-documento-sem-papel-403
  (let [repo (fake-repo-legislativo {})
        r (pt/response-for (service-fn #{"vereador"} repo)
                           :get "/legislativo/documento-modelos"
                           :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 403 (:status r)))))

;; ========================= POST /legislativo/documentos =========================

(deftest gerar-documento-201
  (let [ente (random-uuid) mid (random-uuid) did (random-uuid)
        repo (fake-repo-legislativo
              {:buscar-modelo (fn [_id] (modelo-canonico ente mid))
               :gerar-documento! (fn [_m] {:id did :corpo "Ao Prefeito."})
               :buscar-documento (fn [_id] (documento-canonico ente did))})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post "/legislativo/documentos"
                           :headers (com-bearer (token ente (random-uuid)))
                           :body (json/write-value-as-string
                                   {:modelo-id (str mid) :assunto "Convite [FIXTURE]"
                                    :dados {"destinatario" "Prefeito"}}))]
    (is (= 201 (:status r)))
    (is (= "rascunho" (:estado (ler-json r))))
    (is (nil? (:protocolo-numero (ler-json r))) "ainda nao protocolado")))

(deftest gerar-documento-modelo-inexistente-404
  (let [repo (fake-repo-legislativo {:buscar-modelo (fn [_id] nil)})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post "/legislativo/documentos"
                           :headers (com-bearer (token (random-uuid) (random-uuid)))
                           :body (json/write-value-as-string {:modelo-id (str (random-uuid)) :assunto "Convite"}))]
    (is (= 404 (:status r)))))

(deftest gerar-documento-corpo-invalido-400
  ;; `assunto` ausente -> wire/in.GerarDocumento barra ANTES de qualquer Repo (fake-repo sem metodos: se o
  ;; handler chamasse o Repo mesmo assim, a chamada nil estouraria em vez de 400).
  (let [repo (fake-repo-legislativo {})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post "/legislativo/documentos"
                           :headers (com-bearer (token (random-uuid) (random-uuid)))
                           :body (json/write-value-as-string {:modelo-id "x"}))]
    (is (= 400 (:status r)))))

(deftest gerar-documento-placeholder-faltando-400
  ;; logic/renderizar-documento (via Repo/gerar-documento!) e' fail-closed: `dados` que nao cobre um
  ;; placeholder do template lanca `:tipo :validacao/invalido` -> 400 (nunca 500 por formulario incompleto).
  (let [ente (random-uuid) mid (random-uuid)
        repo (fake-repo-legislativo
              {:buscar-modelo (fn [_id] (modelo-canonico ente mid))
               :gerar-documento! (fn [_m]
                                   (throw (ex-info "renderizar-documento: placeholder sem valor em dados"
                                                   {:tipo :validacao/invalido :chave "destinatario"})))})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post "/legislativo/documentos"
                           :headers (com-bearer (token ente (random-uuid)))
                           :body (json/write-value-as-string {:modelo-id (str mid) :assunto "Convite [FIXTURE]"}))]
    (is (= 400 (:status r)))))

;; ========================= GET /legislativo/documentos/:id =========================

(deftest detalhe-documento-200
  (let [ente (random-uuid) id (random-uuid)
        repo (fake-repo-legislativo {:buscar-documento (fn [_id] (documento-canonico ente id))})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :get (str "/legislativo/documentos/" id)
                           :headers (com-bearer (token ente (random-uuid))))]
    (is (= 200 (:status r)))
    (is (= "rascunho" (:estado (ler-json r))))))

(deftest detalhe-documento-inexistente-404
  (let [repo (fake-repo-legislativo {:buscar-documento (fn [_id] nil)})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :get (str "/legislativo/documentos/" (random-uuid))
                           :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 404 (:status r)))))

(deftest detalhe-documento-com-protocolo-achata-numero-e-ano
  ;; documento-out.protocolo-numero/protocolo-ano sao a projecao ACHATADA do protocolo vinculado — o
  ;; diplomat junta as DUAS leituras (buscar-documento + buscar-protocolo) numa so' resposta.
  (let [ente (random-uuid) id (random-uuid) pid (random-uuid)
        repo (fake-repo-legislativo
              {:buscar-documento (fn [_id] (documento-canonico ente id :estado "emitido" :protocolo-geral-id pid))
               :buscar-protocolo (fn [_id] (protocolo-canonico ente pid id))})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :get (str "/legislativo/documentos/" id)
                           :headers (com-bearer (token ente (random-uuid))))
        corpo (ler-json r)]
    (is (= 200 (:status r)))
    (is (= "emitido" (:estado corpo)))
    (is (= 1 (:protocolo-numero corpo)))
    (is (= 2026 (:protocolo-ano corpo)))))

;; ========================= PATCH /legislativo/documentos/:id =========================

(deftest editar-documento-200
  (let [ente (random-uuid) id (random-uuid)
        repo (fake-repo-legislativo {:buscar-documento (fn [_id] (documento-canonico ente id))
                                      :editar-documento! (fn [m] {:id (:id m)})})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :patch (str "/legislativo/documentos/" id)
                           :headers (com-bearer (token ente (random-uuid)))
                           :body (json/write-value-as-string {:lock-version 0 :corpo "Ao Vice-Prefeito."}))]
    (is (= 200 (:status r)))))

(deftest editar-documento-inexistente-404
  ;; Regressao: pre-check via `buscar-documento-editor` ANTES de chamar `editar-documento` — sem
  ;; `:editar-documento!` no fake-repo, se o handler chamar mesmo assim o teste estoura (nil invocada como fn).
  (let [repo (fake-repo-legislativo {:buscar-documento (fn [_id] nil)})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :patch (str "/legislativo/documentos/" (random-uuid))
                           :headers (com-bearer (token (random-uuid) (random-uuid)))
                           :body (json/write-value-as-string {:lock-version 0 :corpo "Y"}))]
    (is (= 404 (:status r)))))

(deftest editar-documento-conflito-lock-version-400
  ;; db/documento.clj/editar-rascunho! tagueia o conflito de CAS `:tipo :validacao/invalido` (fix desta
  ;; fatia, mirror db/proposicao.clj/editar!) — nunca deveria 500 num PATCH sob concorrencia normal.
  (let [ente (random-uuid) id (random-uuid)
        repo (fake-repo-legislativo
              {:buscar-documento (fn [_id] (documento-canonico ente id))
               :editar-documento! (fn [_m] (throw (ex-info "editar-rascunho!: conflito de lock_version ou inexistente"
                                                            {:tipo :validacao/invalido :id id :lock-version 0})))})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :patch (str "/legislativo/documentos/" id)
                           :headers (com-bearer (token ente (random-uuid)))
                           :body (json/write-value-as-string {:lock-version 0 :corpo "Y"}))]
    (is (= 400 (:status r)))))

;; ========================= POST /legislativo/documentos/:id/protocolo =========================

(deftest protocolar-documento-200-confirma-numero-real
  (let [ente (random-uuid) id (random-uuid) pid (random-uuid) doc (atom (documento-canonico ente id))
        repo (fake-repo-legislativo
              {:buscar-documento (fn [_id] @doc)
               :protocolar-documento! (fn [m]
                                       (reset! doc (documento-canonico ente id :estado "emitido"
                                                                        :protocolo-geral-id pid))
                                       {:documento-id (:documento-id m) :protocolo-numero 1
                                        :protocolo-ano (:ano m) :protocolo-id pid})
               :buscar-protocolo (fn [_id] (protocolo-canonico ente pid id))})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post (str "/legislativo/documentos/" id "/protocolo")
                           :headers (com-bearer (token ente (random-uuid)))
                           :body (json/write-value-as-string {:lock-version 0}))
        corpo (ler-json r)]
    (is (= 200 (:status r)))
    (is (= "emitido" (:estado corpo)))
    (is (= 1 (:protocolo-numero corpo)) "numero REAL, pos-protocolar (nunca placeholder)")))

(deftest protocolar-documento-inexistente-404
  (let [repo (fake-repo-legislativo {:buscar-documento (fn [_id] nil)})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post (str "/legislativo/documentos/" (random-uuid) "/protocolo")
                           :headers (com-bearer (token (random-uuid) (random-uuid)))
                           :body (json/write-value-as-string {:lock-version 0}))]
    (is (= 404 (:status r)))))

(deftest protocolar-documento-ja-emitido-400
  ;; re-protocolar um documento ja' 'emitido' -> emitir! (dentro de protocolar-documento!) lanca
  ;; `:tipo :validacao/invalido` (fix desta fatia) -> 400, pre-condicao de negocio conhecida, nunca 500.
  (let [ente (random-uuid) id (random-uuid)
        repo (fake-repo-legislativo
              {:buscar-documento (fn [_id] (documento-canonico ente id :estado "emitido"))
               :protocolar-documento! (fn [_m]
                                       (throw (ex-info "emitir!: so se emite um documento 'rascunho'"
                                                       {:tipo :validacao/invalido :id id :estado "emitido"})))})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post (str "/legislativo/documentos/" id "/protocolo")
                           :headers (com-bearer (token ente (random-uuid)))
                           :body (json/write-value-as-string {:lock-version 1}))]
    (is (= 400 (:status r)))))

(deftest protocolar-documento-lock-version-desatualizado-400
  (let [ente (random-uuid) id (random-uuid)
        repo (fake-repo-legislativo
              {:buscar-documento (fn [_id] (documento-canonico ente id))
               :protocolar-documento! (fn [_m]
                                       (throw (ex-info "emitir!: conflito de lock_version ou inexistente"
                                                       {:tipo :validacao/invalido :id id :lock-version 0})))})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post (str "/legislativo/documentos/" id "/protocolo")
                           :headers (com-bearer (token ente (random-uuid)))
                           :body (json/write-value-as-string {:lock-version 0}))]
    (is (= 400 (:status r)))))

;; ========================= GET /legislativo/protocolo-geral =========================

(deftest listar-protocolo-do-ano-200
  (let [ente (random-uuid) pid (random-uuid) did (random-uuid)
        repo (fake-repo-legislativo {:protocolos-do-ano (fn [_ano] [(protocolo-canonico ente pid did)])})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :get "/legislativo/protocolo-geral"
                           :headers (com-bearer (token ente (random-uuid))))]
    (is (= 200 (:status r)))
    (is (= 1 (count (:itens (ler-json r)))))))

(deftest listar-protocolo-do-ano-sem-papel-403
  (let [repo (fake-repo-legislativo {})
        r (pt/response-for (service-fn #{"vereador"} repo)
                           :get "/legislativo/protocolo-geral"
                           :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 403 (:status r)))))
