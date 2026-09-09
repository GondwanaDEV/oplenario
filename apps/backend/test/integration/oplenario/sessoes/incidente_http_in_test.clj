(ns oplenario.sessoes.incidente-http-in-test
  "Slice §16.13 — incidentes processuais da sessao (mesa de conducao ao vivo). `POST /sessoes/:id/incidentes`
  registra um ato regimental APPEND-ONLY (pedido de vista, verificacao de votacao, urgencia, votacao em bloco)
  -> 201 {:id}. `created-by` e INJETADO do `ator` (nunca do corpo); tipo/resultado validados contra o enum e
  descricao nao-vazia na BORDA -> 400 (nunca o CHECK da migration -> 500). `objeto-tipo`/`objeto-id` coerentes
  (ambos ou nenhum). Carrega a sessao (nil->404), pode-ver-sessao? (mesma Casa->403). `objeto`/`requerente` sao
  forward-ref (sem checagem cross-module — mesma classe de carry de presenca/tribuna). DB-free: RepoSessoes FAKE
  + idp-dev real — espelha o tribuna-decisao-http-in-test."
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
            [oplenario.sessoes.components.repositorio :as repo-sessoes])
  (:import (java.time Instant)))

(defn- sessao-canonica [ente-id id]
  {:id id :ente-id ente-id :estado "aberta" :tipo-sessao "ordinaria"})

(defn- fake-repo-sessoes
  "RepoSessoes fake (parcial): `buscar-sessao` resolve a sessao; `registrar-incidente!` GRAVA o mapa em
  `capturado` e ECOA o recibo {:id}."
  [busca-fn capturado]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ ente-id id] (busca-fn ente-id id))
    (registrar-incidente! [_ ente-id m]
      (reset! capturado (assoc m :ente-id ente-id))
      {:id (:id m)})))

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
(defn- url-incidentes [sid] (str "/sessoes/" sid "/incidentes"))

(def ^:private incidente-valido
  {"tipo" "pedido_vista"
   "resultado" "deferido"
   "descricao" "Pedido de vista da Proposicao 12/2026 pelo Ver. Fulano"
   "ocorrido-em" "2026-06-30T14:00:00Z"})

;; ---------- POST /sessoes/:id/incidentes (sucesso) ----------

(deftest incidente-201
  (let [ente (random-uuid) sid (random-uuid) op (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) cap)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-incidentes sid)
                           :headers (com-json (token ente op))
                           :body (corpo incidente-valido))
        body (ler-json r)]
    (is (= 201 (:status r)) "papel + mesma Casa + corpo valido -> 201")
    (is (= (str (:id @cap)) (:id body)) "recibo carrega o id do incidente gravado")
    (is (= sid (:sessao-id @cap)) "Repo recebeu a sessao-id (path)")
    (is (= "pedido_vista" (:tipo @cap)) "Repo recebeu o tipo")
    (is (= "deferido" (:resultado @cap)) "Repo recebeu o resultado")
    (is (= (Instant/parse "2026-06-30T14:00:00Z") (:ocorrido-em @cap)) "ocorrido-em coagido a Instant")
    (is (= op (:created-by @cap)) "created-by INJETADO do ator")
    (is (some? (:id @cap)) "id gerado server-side (PK NOT NULL)")
    (is (nil? (:objeto-id @cap)) "sem objeto no corpo -> nil")))

(deftest incidente-com-objeto-201
  (let [ente (random-uuid) prop (random-uuid) req (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) cap)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-incidentes (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc incidente-valido
                                               "objeto-tipo" "proposicao" "objeto-id" (str prop)
                                               "requerente-id" (str req)
                                               "deliberacao" "Vista por 1 sessao, art. 132 RI")))]
    (is (= 201 (:status r)) "incidente com objeto/requerente/deliberacao -> 201")
    (is (= "proposicao" (:objeto-tipo @cap)) "Repo recebeu o objeto-tipo")
    (is (= prop (:objeto-id @cap)) "objeto-id coagido a uuid")
    (is (= req (:requerente-id @cap)) "requerente-id coagido a uuid")
    (is (= "Vista por 1 sessao, art. 132 RI" (:deliberacao @cap)) "Repo recebeu a deliberacao")))

;; ---------- authz / not-found ----------

(deftest incidente-sessao-encerrada-409
  ;; T2 grupo A achado #4 (ledger de prontidao Fase 8): mesmo gate `exigir-sessao-aberta!`.
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (assoc (sessao-canonica ente id) :estado "encerrada")) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-incidentes (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo incidente-valido))]
    (is (= 409 (:status r)) "sessao ja encerrada -> 409")))

(deftest incidente-sessao-inexistente-404
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-incidentes (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo incidente-valido))]
    (is (= 404 (:status r)) "sessao inexistente -> 404")))

(deftest incidente-casa-alheia-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica (random-uuid) id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-incidentes (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo incidente-valido))]
    (is (= 403 (:status r)) "sessao de ente alheio -> 403")))

(deftest incidente-sem-papel-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"vereador"} repo-s)
                           :post (url-incidentes (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo incidente-valido))]
    (is (= 403 (:status r)) "sem papel 'secretario' -> 403")))

(deftest incidente-sem-token-401
  (let [repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-incidentes (random-uuid))
                           :headers {"Content-Type" "application/json"}
                           :body (corpo incidente-valido))]
    (is (= 401 (:status r)) "sem token -> 401")))

;; ---------- validacao de borda -> 400 ----------

(deftest incidente-tipo-invalido-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-incidentes (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc incidente-valido "tipo" "greve")))]
    (is (= 400 (:status r)) "tipo fora do enum -> 400 na borda, nunca 500 do CHECK")))

(deftest incidente-resultado-invalido-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-incidentes (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc incidente-valido "resultado" "talvez")))]
    (is (= 400 (:status r)) "resultado fora do enum -> 400")))

(deftest incidente-descricao-em-branco-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-incidentes (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc incidente-valido "descricao" "   ")))]
    (is (= 400 (:status r)) "descricao so espacos (trim) -> 400 (espelha o CHECK length(trim)>0)")))

(deftest incidente-descricao-ausente-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-incidentes (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (dissoc incidente-valido "descricao")))]
    (is (= 400 (:status r)) "corpo sem descricao (obrigatoria) -> 400")))

(deftest incidente-ocorrido-em-invalido-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-incidentes (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc incidente-valido "ocorrido-em" "ontem")))]
    (is (= 400 (:status r)) "ocorrido-em nao-ISO-8601 -> 400")))

(deftest incidente-objeto-incoerente-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-incidentes (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc incidente-valido "objeto-tipo" "proposicao")))]
    (is (= 400 (:status r)) "objeto-tipo sem objeto-id -> 400 na borda (espelha o CHECK de coerencia)")))

(deftest incidente-objeto-id-malformado-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-incidentes (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc incidente-valido "objeto-tipo" "proposicao"
                                               "objeto-id" "nao-e-uuid")))]
    (is (= 400 (:status r)) "objeto-id malformado -> 400, nunca 500")))

(deftest incidente-deliberacao-em-branco-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-incidentes (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc incidente-valido "deliberacao" "   ")))]
    (is (= 400 (:status r)) "deliberacao presente mas em branco -> 400 (espelha o CHECK)")))

(deftest incidente-campo-extra-created-by-ignorado-201
  (let [ente (random-uuid) op (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) cap)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-incidentes (random-uuid))
                           :headers (com-json (token ente op))
                           ;; created-by NAO vem do corpo (injetado do ator); o allowlist (so-esperados) o
                           ;; descarta -> nunca chega forjado ao Repo.
                           :body (corpo (assoc incidente-valido "created-by" (str (random-uuid)))))]
    (is (= 201 (:status r)) "campo extra 'created-by' ignorado pelo allowlist -> 201")
    (is (= op (:created-by @cap)) "created-by no Repo e o do ator, nunca o forjado")))
