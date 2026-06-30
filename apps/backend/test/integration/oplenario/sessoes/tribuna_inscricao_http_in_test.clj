(ns oplenario.sessoes.tribuna-inscricao-http-in-test
  "Slice F4 — eixo F (§22.6), tribuna camada de INTENCAO (F4.5a): a borda HTTP da inscricao de oradores.
  `POST /sessoes/:id/inscricoes` inscreve (corpo {vereador-id, origem-inscricao, fase, proposicao-ref-id?}) ->
  201 {:id :ordem}; `POST /sessoes/:id/inscricoes/:insc-id/desistir` move inscrita->desistencia (corpo
  {lock-version}) -> 200, conflito/CAS/ja-desistiu -> 409. O Repo ja compoe o ato + emite
  inscricao.registrada/desistida (fila ao vivo) na MESMA tx — este slice so fecha a borda. Carrega a sessao
  (nil->404), pode-ver-sessao? (mesma Casa->403); origem/fase fora do enum / uuid malformado / lock fora de int4
  -> 400. DB-free: RepoSessoes FAKE + idp-dev real — espelha o presenca-http-in-test."
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

(defn- sessao-canonica [ente-id id]
  {:id id :ente-id ente-id :estado "aberta" :tipo-sessao "ordinaria"})

(defn- fake-repo-sessoes
  "RepoSessoes fake (parcial): `buscar-sessao` resolve a sessao; `inscrever!` GRAVA o mapa em `capturado` e ECOA
  {:id :ordem}; `desistir!` ECOA {:de :para} (ou LANCA :conflito/inscricao se `conflito?`)."
  [busca-fn capturado & {:keys [conflito?]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ ente-id id] (busca-fn ente-id id))
    (inscrever! [_ ente-id m]
      (reset! capturado (assoc m :ente-id ente-id))
      {:id (:id m) :ordem 1})
    (desistir! [_ ente-id m]
      (when conflito?
        (throw (ex-info "desistir!: transicao invalida" {:tipo :conflito/inscricao :id (:id m)})))
      (reset! capturado (assoc m :ente-id ente-id))
      {:de "inscrita" :para "desistencia"})))

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
(defn- com-json [tok] {"authorization" (str "Bearer " tok) "Content-Type" "application/json"})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))
(defn- corpo [m] (json/write-value-as-string m))
(defn- url-inscrever [sid] (str "/sessoes/" sid "/inscricoes"))
(defn- url-desistir [sid iid] (str "/sessoes/" sid "/inscricoes/" iid "/desistir"))

(def ^:private inscricao-valida
  {"vereador-id" nil "origem-inscricao" "pre_sessao_secretaria" "fase" "expediente"})

;; ---------- POST /sessoes/:id/inscricoes (inscrever) ----------

(deftest inscrever-201
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) cap)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-inscrever sid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc inscricao-valida "vereador-id" (str vid))))
        body (ler-json r)]
    (is (= 201 (:status r)) "papel + mesma Casa + corpo valido -> 201")
    (is (= (str (:id @cap)) (:id body)) "recibo carrega o id da inscricao")
    (is (= 1 (:ordem body)) "recibo carrega a ordem na fila")
    (is (= sid (:sessao-id @cap)) "Repo recebeu a sessao-id (path)")
    (is (= vid (:vereador-id @cap)) "Repo recebeu o vereador-id (corpo)")
    (is (= "pre_sessao_secretaria" (:origem-inscricao @cap)) "Repo recebeu a origem")
    (is (= "expediente" (:fase @cap)) "Repo recebeu a fase")
    (is (some? (:created-by @cap)) "Repo recebeu created-by (do ator)")))

(deftest inscrever-com-proposicao-ref-201
  (let [ente (random-uuid) sid (random-uuid) pid (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) cap)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-inscrever sid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc inscricao-valida "vereador-id" (str (random-uuid))
                                               "proposicao-ref-id" (str pid))))]
    (is (= 201 (:status r)) "proposicao-ref-id opcional aceito -> 201")
    (is (= pid (:proposicao-ref-id @cap)) "Repo recebeu o proposicao-ref-id coagido")))

(deftest inscrever-sessao-inexistente-404
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-inscrever (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc inscricao-valida "vereador-id" (str (random-uuid)))))]
    (is (= 404 (:status r)) "sessao inexistente -> 404")))

(deftest inscrever-casa-alheia-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica (random-uuid) id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-inscrever (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc inscricao-valida "vereador-id" (str (random-uuid)))))]
    (is (= 403 (:status r)) "sessao de ente alheio -> 403")))

(deftest inscrever-sem-papel-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"vereador"} repo-s)
                           :post (url-inscrever (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc inscricao-valida "vereador-id" (str (random-uuid)))))]
    (is (= 403 (:status r)) "sem papel 'secretario' -> 403")))

(deftest inscrever-sem-token-401
  (let [repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-inscrever (random-uuid))
                           :headers {"Content-Type" "application/json"}
                           :body (corpo (assoc inscricao-valida "vereador-id" (str (random-uuid)))))]
    (is (= 401 (:status r)) "sem token -> 401")))

(deftest inscrever-origem-desconhecida-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-inscrever (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc inscricao-valida "vereador-id" (str (random-uuid))
                                               "origem-inscricao" "telepatia")))]
    (is (= 400 (:status r)) "origem-inscricao fora do enum -> 400")))

(deftest inscrever-fase-invalida-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-inscrever (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc inscricao-valida "vereador-id" (str (random-uuid))
                                               "fase" "intervalo")))]
    (is (= 400 (:status r)) "fase fora do enum -> 400")))

(deftest inscrever-vereador-id-malformado-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-inscrever (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc inscricao-valida "vereador-id" "nao-e-uuid")))]
    (is (= 400 (:status r)) "vereador-id malformado -> 400")))

(deftest inscrever-proposicao-ref-malformada-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-inscrever (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc inscricao-valida "vereador-id" (str (random-uuid))
                                               "proposicao-ref-id" "xyz")))]
    (is (= 400 (:status r)) "proposicao-ref-id presente mas malformado -> 400")))

;; ---------- POST /sessoes/:id/inscricoes/:insc-id/desistir ----------

(deftest desistir-200
  (let [ente (random-uuid) sid (random-uuid) iid (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) cap)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-desistir sid iid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"lock-version" 0}))
        body (ler-json r)]
    (is (= 200 (:status r)) "desistencia valida -> 200 (atualiza, nao cria)")
    (is (= "inscrita" (:de body)) "recibo carrega o estado de origem")
    (is (= "desistencia" (:para body)) "recibo carrega o estado de destino")
    (is (= iid (:id @cap)) "Repo recebeu o id da inscricao (path)")
    (is (= 0 (:lock-version @cap)) "Repo recebeu o lock-version (CAS)")
    (is (some? (:updated-by @cap)) "Repo recebeu updated-by (do ator)")))

(deftest desistir-conflito-409
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil) :conflito? true)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-desistir (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"lock-version" 0}))]
    (is (= 409 (:status r)) "ja desistiu / lock-stale -> 409 (nao 500)")))

(deftest desistir-sessao-inexistente-404
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-desistir (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"lock-version" 0}))]
    (is (= 404 (:status r)) "sessao inexistente -> 404")))

(deftest desistir-lock-version-ausente-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-desistir (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {}))]
    (is (= 400 (:status r)) "corpo sem lock-version -> 400")))

(deftest desistir-insc-id-malformado-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (str "/sessoes/" (random-uuid) "/inscricoes/nao-e-uuid/desistir")
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"lock-version" 0}))]
    (is (= 400 (:status r)) ":insc-id malformado no path -> 400, nunca 500")))
