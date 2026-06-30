(ns oplenario.sessoes.sessao-transicao-http-in-test
  "Slice F4 — eixo A/G (§22.6): a borda HTTP da MESA DE CONDUCAO. `POST /sessoes/:id/transicao` move o estado
  da sessao pela maquina (agendada->aberta->...->encerrada->arquivada), corpo {para, lock-version, motivo?}. O
  Repo ja compoe o ato + emite `sessao.transicionou` (que o canal SSE do plenario consome) na MESMA tx — este
  slice so fecha a borda. Carrega a sessao (nil->404), pode-ver-sessao? (mesma Casa->403); transicao invalida /
  lock-stale -> 409; `para` desconhecido / nao_realizada-sem-motivo / lock fora de int4 -> 400. DB-free: RepoSessoes
  FAKE + idp-dev real — espelha o gravacao-vincular-http-in-test (W3)."
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
  "Sessao como buscar-sessao devolve (kebab) — so o que a authz fina (pode-ver-sessao? = mesma Casa) le."
  [ente-id id]
  {:id id :ente-id ente-id :estado "agendada" :tipo-sessao "ordinaria"})

(defn- fake-repo-sessoes
  "RepoSessoes fake (parcial): `buscar-sessao` resolve a sessao; `transicionar-sessao!` ECOA {:de :para} e GRAVA
  o mapa recebido em `capturado` — p/ provar para/lock-version/updated-by/motivo passados ao Repo. Se `conflito?`,
  `transicionar-sessao!` LANCA o ex-info de maquina/CAS (`:tipo :conflito/transicao` -> 409)."
  [busca-fn capturado & {:keys [conflito?]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ ente-id id] (busca-fn ente-id id))
    (transicionar-sessao! [_ ente-id m]
      (when conflito?
        (throw (ex-info "transicionar!: transicao de estado invalida"
                        {:tipo :conflito/transicao :id (:id m)})))
      (reset! capturado (assoc m :ente-id ente-id))
      {:de "agendada" :para (:para m)})))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- service-fn*
  [papeis repo-s]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-sessoes repo-s
                                   :objeto-store nil})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id]
  (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))

(defn- com-json [tok] {"authorization" (str "Bearer " tok) "Content-Type" "application/json"})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))
(defn- url [sid] (str "/sessoes/" sid "/transicao"))
(defn- corpo [m] (json/write-value-as-string m))

;; ---------- POST /sessoes/:id/transicao ----------

(deftest transicao-200
  (let [ente (random-uuid) sid (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) cap)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url sid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"para" "aberta" "lock-version" 0}))
        body (ler-json r)]
    (is (= 200 (:status r)) "papel + sessao da mesma Casa + transicao valida -> 200")
    (is (= (str sid) (:sessao-id body)) "recibo carrega a sessao-id")
    (is (= "agendada" (:de body)) "recibo carrega o estado de origem")
    (is (= "aberta" (:para body)) "recibo carrega o estado de destino")
    (is (= "aberta" (:para @cap)) "o Repo recebeu o estado-alvo")
    (is (= 0 (:lock-version @cap)) "o Repo recebeu o lock-version (CAS otimista)")
    (is (= sid (:id @cap)) "o Repo recebeu o id da sessao (uuid coagido na borda)")))

(deftest transicao-nao-realizada-com-motivo-200
  (let [ente (random-uuid) sid (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) cap)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url sid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"para" "nao_realizada" "lock-version" 0 "motivo" "falta de quorum"}))]
    (is (= 200 (:status r)) "nao_realizada COM motivo -> 200")
    (is (= "falta de quorum" (:motivo @cap)) "o Repo recebeu o motivo (vai p/ motivo_nao_realizada)")))

(deftest transicao-sessao-inexistente-404
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"para" "aberta" "lock-version" 0}))]
    (is (= 404 (:status r)) "sessao inexistente no tenant -> 404")))

(deftest transicao-casa-alheia-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica (random-uuid) id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"para" "aberta" "lock-version" 0}))]
    (is (= 403 (:status r)) "sessao de ente alheio -> pode-ver-sessao? nega -> 403")))

(deftest transicao-invalida-ou-lock-stale-409
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil) :conflito? true)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"para" "arquivada" "lock-version" 0}))]
    (is (= 409 (:status r)) "transicao invalida pela maquina / lock-stale -> 409 (nao 500)")))

(deftest transicao-sem-papel-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"vereador"} repo-s)
                           :post (url (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"para" "aberta" "lock-version" 0}))]
    (is (= 403 (:status r)) "ator sem papel 'secretario' -> authz grossa nega -> 403")))

(deftest transicao-sem-token-401
  (let [repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid))
                           :headers {"Content-Type" "application/json"}
                           :body (corpo {"para" "aberta" "lock-version" 0}))]
    (is (= 401 (:status r)) "rota herda a cadeia de auth: sem token -> 401 (fail-closed)")))

(deftest transicao-para-desconhecido-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"para" "voando" "lock-version" 0}))]
    (is (= 400 (:status r)) "estado-alvo fora do enum -> 400 (fail-closed na borda, nunca 500)")))

(deftest transicao-nao-realizada-sem-motivo-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"para" "nao_realizada" "lock-version" 0}))]
    (is (= 400 (:status r)) "nao_realizada SEM motivo -> 400 na borda (antes do 500 do db)")))

(deftest transicao-lock-version-ausente-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"para" "aberta"}))]
    (is (= 400 (:status r)) "corpo sem lock-version -> 400 (validacao na borda)")))

(deftest transicao-motivo-longo-demais-400
  ;; campo livre com teto (defense-in-depth): motivo > 2000 chars -> 400 na borda, nunca persiste desmesurado.
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"para" "nao_realizada" "lock-version" 0
                                         "motivo" (apply str (repeat 2001 "x"))}))]
    (is (= 400 (:status r)) "motivo acima do teto (2001 chars) -> 400 (fail-closed na borda)")))

(deftest transicao-id-malformado-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post "/sessoes/nao-e-uuid/transicao"
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"para" "aberta" "lock-version" 0}))]
    (is (= 400 (:status r)) "sessao :id malformado no path -> 400, nunca 500")))
