(ns oplenario.fluxo-comentarios-test
  "E2E da BORDA HTTP dos comentarios/moderacao (FAST-FOLLOW Slice 6, feature 6.3) — a vertical de rota
  ponta-a-ponta (adapters/in -> controller -> repo -> adapters/out -> wire/out) + os perfis de authz. DB-free:
  RepoParticipacao FAKE (reify) + idp-dev real (precedente compliance/painel_http_in_test; fluxo-ouvidoria-test).
  Relogio FIXO injetado no fragmento de rotas. Foco de seguranca: o autor SEMPRE vem do ator (nunca do corpo);
  a lista publica nunca vaza pendente/rejeitado; moderar rejeitado sem motivo -> 400; denunciar 2x -> ainda
  200 (idempotente, nunca 409); a rota de fila de moderacao vive em `/moderacao/comentarios` (nao
  `/comentarios/moderacao`) — DECISAO DE ROTEAMENTO: o router prefix-tree do Pedestal 0.7 nao admite um
  literal ('moderacao') e um wildcard (':id', de POST /comentarios/:id/moderar) no MESMO nivel de path sob
  '/comentarios' (mesma limitacao ja documentada em participacao/diplomat/http/in — ver o disambiguador
  'casa/' das rotas publicas); trocar a ORDEM dos segmentos da rota de listagem evita a colisao sem inventar
  mais um segmento estatico."
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.participacao.components.repositorio :as repo-part]
            [oplenario.participacao.diplomat.http.in :as participacao-http])
  (:import (java.time Instant)))

(def ^:private t0 (Instant/parse "2026-07-03T12:00:00Z"))
(def ^:private relogio (tempo/relogio-fixo t0))

(defn- fake-repo-participacao
  [{:keys [comentar buscar-comentario moderar-comentario denunciar-comentario
           comentarios-da-materia fila-moderacao]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-part/RepoParticipacao
    (comentar! [_ _ente _m] comentar)
    (buscar-comentario [_ _ente _id] buscar-comentario)
    (moderar-comentario! [_ _ente _m] moderar-comentario)
    (denunciar-comentario! [_ _ente _m] denunciar-comentario)
    (comentarios-da-materia [_ _ente _prop] comentarios-da-materia)
    (fila-moderacao [_ _ente] fila-moderacao)))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "cidadao"} :papeis papeis})))

(defn- service-fn [papeis repo-part]
  (let [auth  (it/autenticacao (idp-dev/idp-dev) (fake-repo-identidade papeis))
        rotas (participacao-http/rotas {:auth auth :repo-participacao repo-part
                                        :resolver-ente-publico participacao-http/resolver-ente-publico-uuid
                                        :relogio relogio})]
    (-> (http/servico (config/carregar) rotas it/globais)
        ph/create-server ::ph/service-fn)))

(defn- token [ente-id ident-id]
  (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))
(defn- com-bearer [tok] {"authorization" (str "Bearer " tok)})
(defn- json-headers [tok] (merge (com-bearer tok) {"Content-Type" "application/json"}))
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

;; ---------- POST /portal/materias/:proposicao_id/comentarios (cidadao, so-auth) ----------

(deftest comentar-201-cidadao-autenticado
  (let [prop (random-uuid)
        repo (fake-repo-participacao {:comentar {:id (random-uuid) :estado "pendente"}})
        r    (pt/response-for (service-fn #{} repo) :post (str "/portal/materias/" prop "/comentarios")
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:corpo "Apoio esta proposicao."}))
        body (ler-json r)]
    (is (= 201 (:status r)))
    (is (= "pendente" (:estado body)))))

(deftest comentar-sem-auth-401
  (let [prop (random-uuid)
        repo (fake-repo-participacao {:comentar {:id (random-uuid) :estado "pendente"}})
        r    (pt/response-for (service-fn #{} repo) :post (str "/portal/materias/" prop "/comentarios")
                              :headers {"content-type" "application/json"}
                              :body (json/write-value-as-string {:corpo "x"}))]
    (is (= 401 (:status r)))))

(deftest comentar-corpo-vazio-400
  (let [prop (random-uuid)
        repo (fake-repo-participacao {:comentar {:id (random-uuid) :estado "pendente"}})
        r    (pt/response-for (service-fn #{} repo) :post (str "/portal/materias/" prop "/comentarios")
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:corpo "   "}))]
    (is (= 400 (:status r)))))

(deftest comentar-corpo-acima-do-teto-400
  (let [prop (random-uuid)
        repo (fake-repo-participacao {:comentar {:id (random-uuid) :estado "pendente"}})
        r    (pt/response-for (service-fn #{} repo) :post (str "/portal/materias/" prop "/comentarios")
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:corpo (apply str (repeat 2001 "a"))}))]
    (is (= 400 (:status r)) "corpo > 2000 chars -> 400 fail-closed (espelha o CHECK da mig 0043)")))

(deftest comentar-proposicao-id-malformado-400
  (let [repo (fake-repo-participacao {:comentar {:id (random-uuid) :estado "pendente"}})
        r    (pt/response-for (service-fn #{} repo) :post "/portal/materias/nao-e-uuid/comentarios"
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:corpo "x"}))]
    (is (= 400 (:status r)))))

;; ---------- GET /portal/casa/:ente/materias/:proposicao_id/comentarios (PUBLICA, sem auth) ----------

(deftest comentarios-da-materia-publico-200-sem-pii
  (let [prop (random-uuid)
        repo (fake-repo-participacao
              {:comentarios-da-materia [{:id (random-uuid) :corpo "Otima ideia." :criado-em t0}]})
        r    (pt/response-for (service-fn #{} repo)
                              :get (str "/portal/casa/" (random-uuid) "/materias/" prop "/comentarios"))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= 1 (count body)))
    (is (= "Otima ideia." (:corpo (first body))))
    (is (not (contains? (first body) :autor-identidade-id)))
    (is (not (contains? (first body) :ente-id)))))

(deftest comentarios-da-materia-ente-malformado-400
  (let [repo (fake-repo-participacao {:comentarios-da-materia []})
        r    (pt/response-for (service-fn #{} repo)
                              :get (str "/portal/casa/nao-e-uuid/materias/" (random-uuid) "/comentarios"))]
    (is (= 400 (:status r)))))

;; ---------- POST /portal/comentarios/:id/denunciar (cidadao, so-auth, idempotente) ----------

(deftest denunciar-200-cidadao-autenticado
  (let [cid  (random-uuid)
        repo (fake-repo-participacao {:buscar-comentario {:id cid :estado "pendente"}
                                      :denunciar-comentario {:denunciado true}})
        r    (pt/response-for (service-fn #{} repo) :post (str "/portal/comentarios/" cid "/denunciar")
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:motivo "spam"}))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (true? (:denunciado body)))))

(deftest denunciar-2x-e-idempotente-nunca-409
  (let [cid  (random-uuid)
        repo (fake-repo-participacao {:buscar-comentario {:id cid :estado "pendente"}
                                      :denunciar-comentario {:denunciado true}})
        svc  (service-fn #{} repo)
        tok  (token (random-uuid) (random-uuid))
        r1   (pt/response-for svc :post (str "/portal/comentarios/" cid "/denunciar")
                              :headers (json-headers tok) :body (json/write-value-as-string {:motivo "spam"}))
        r2   (pt/response-for svc :post (str "/portal/comentarios/" cid "/denunciar")
                              :headers (json-headers tok) :body (json/write-value-as-string {:motivo "spam"}))]
    (is (= 200 (:status r1)))
    (is (= 200 (:status r2)) "2a denuncia do mesmo cidadao -> AINDA 200 (idempotente, nunca 409)")))

(deftest denunciar-comentario-inexistente-404
  (let [cid  (random-uuid)
        repo (fake-repo-participacao {:denunciar-comentario nil})
        r    (pt/response-for (service-fn #{} repo) :post (str "/portal/comentarios/" cid "/denunciar")
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:motivo "spam"}))]
    (is (= 404 (:status r)))))

(deftest denunciar-sem-auth-401
  (let [cid  (random-uuid)
        repo (fake-repo-participacao {:denunciar-comentario {:denunciado true}})
        r    (pt/response-for (service-fn #{} repo) :post (str "/portal/comentarios/" cid "/denunciar")
                              :headers {"content-type" "application/json"}
                              :body (json/write-value-as-string {:motivo "spam"}))]
    (is (= 401 (:status r)))))

;; ---------- GET /moderacao/comentarios (SERVIDOR) ----------

(deftest fila-moderacao-servidor-200
  (let [repo (fake-repo-participacao
              {:fila-moderacao [{:id (random-uuid) :proposicao-id (random-uuid)
                                 :autor-identidade-id (random-uuid) :corpo "x" :denunciado true :criado-em t0}]})
        r    (pt/response-for (service-fn #{"secretario"} repo) :get "/moderacao/comentarios"
                              :headers (com-bearer (token (random-uuid) (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= 1 (count body)))
    (is (true? (:denunciado (first body))))))

(deftest fila-moderacao-sem-papel-403
  (let [repo (fake-repo-participacao {:fila-moderacao []})
        r    (pt/response-for (service-fn #{} repo) :get "/moderacao/comentarios"
                              :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 403 (:status r)))))

;; ---------- POST /comentarios/:id/moderar (SERVIDOR) ----------

(deftest moderar-aprovado-servidor-200
  (let [cid  (random-uuid)
        repo (fake-repo-participacao {:moderar-comentario {:id cid :estado "aprovado"}})
        r    (pt/response-for (service-fn #{"secretario"} repo) :post (str "/comentarios/" cid "/moderar")
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:acao "aprovado"}))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= "aprovado" (:estado body)))))

(deftest moderar-rejeitado-com-motivo-valido-200
  (let [cid  (random-uuid)
        repo (fake-repo-participacao {:moderar-comentario {:id cid :estado "rejeitado"}})
        r    (pt/response-for (service-fn #{"secretario"} repo) :post (str "/comentarios/" cid "/moderar")
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:acao "rejeitado" :motivo-rejeicao "spam"}))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= "rejeitado" (:estado body)))))

(deftest moderar-rejeitado-sem-motivo-400
  (let [cid  (random-uuid)
        repo (fake-repo-participacao {:moderar-comentario {:id cid :estado "rejeitado"}})
        r    (pt/response-for (service-fn #{"secretario"} repo) :post (str "/comentarios/" cid "/moderar")
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:acao "rejeitado"}))]
    (is (= 400 (:status r)) "motivo_rejeicao obrigatorio quando acao=rejeitado -> 400 fail-closed")))

(deftest moderar-rejeitado-motivo-fora-do-vocabulario-400
  (let [cid  (random-uuid)
        repo (fake-repo-participacao {:moderar-comentario {:id cid :estado "rejeitado"}})
        r    (pt/response-for (service-fn #{"secretario"} repo) :post (str "/comentarios/" cid "/moderar")
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:acao "rejeitado" :motivo-rejeicao "porque sim"}))]
    (is (= 400 (:status r)) "motivo fora dos 5 fixos -> 400 fail-closed")))

(deftest moderar-acao-invalida-400
  (let [cid  (random-uuid)
        repo (fake-repo-participacao {:moderar-comentario {:id cid :estado "aprovado"}})
        r    (pt/response-for (service-fn #{"secretario"} repo) :post (str "/comentarios/" cid "/moderar")
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:acao "pendente"}))]
    (is (= 400 (:status r)) "'pendente' nao e' uma ACAO de moderacao (so' aprovado|rejeitado)")))

(deftest moderar-ja-terminal-409
  (let [cid  (random-uuid)
        repo (fake-repo-participacao {:moderar-comentario nil :buscar-comentario {:id cid :estado "aprovado"}})
        r    (pt/response-for (service-fn #{"secretario"} repo) :post (str "/comentarios/" cid "/moderar")
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:acao "rejeitado" :motivo-rejeicao "spam"}))]
    (is (= 409 (:status r)))))

(deftest moderar-comentario-inexistente-404
  (let [cid  (random-uuid)
        repo (fake-repo-participacao {:moderar-comentario nil :buscar-comentario nil})
        r    (pt/response-for (service-fn #{"secretario"} repo) :post (str "/comentarios/" cid "/moderar")
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:acao "aprovado"}))]
    (is (= 404 (:status r)))))

(deftest moderar-sem-papel-403
  (let [cid  (random-uuid)
        repo (fake-repo-participacao {:moderar-comentario {:id cid :estado "aprovado"}})
        r    (pt/response-for (service-fn #{} repo) :post (str "/comentarios/" cid "/moderar")
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:acao "aprovado"}))]
    (is (= 403 (:status r)))))
