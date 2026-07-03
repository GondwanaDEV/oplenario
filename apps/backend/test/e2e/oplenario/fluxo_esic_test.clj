(ns oplenario.fluxo-esic-test
  "E2E da BORDA HTTP do e-SIC (F6 Slice 1) — a vertical de rota ponta-a-ponta (adapters/in -> controller ->
  repo -> adapters/out -> wire/out) + os TRES perfis de authz. DB-free: RepoParticipacao FAKE (reify) +
  idp-dev real (precedente compliance/painel_http_in_test). Relogio FIXO injetado no fragmento de rotas
  (determinismo do dias-restantes). Foco de seguranca: a rota PUBLICA nao vaza PII e o :ente malformado
  fail-closa (400), nunca cross-tenant; a rota do dono aplica policy FINA (403)."
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
  (:import (java.time Instant LocalDate)))

;; relogio fixo: 12:00Z de 2026-07-03 -> zona civil America/Fortaleza = 2026-07-03; vence (fixture) 2026-07-23
;; -> dias-restantes = 20.
(def ^:private t0 (Instant/parse "2026-07-03T12:00:00Z"))
(def ^:private relogio (tempo/relogio-fixo t0))
(def ^:private vence (LocalDate/of 2026 7 23))

(defn- fake-repo-participacao [{:keys [protocolar acompanhar pedido-com-prazo]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-part/RepoParticipacao
    (protocolar-pedido! [_ _ente _m] protocolar)
    (acompanhar-por-protocolo [_ _ente _protocolo] acompanhar)
    (pedido-com-prazo [_ _ente _id] pedido-com-prazo)))

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

;; ---------- POST /portal/esic/pedidos (cidadao atribuido: SO auth, SEM papel) ----------

(deftest protocolar-201-cidadao-sem-papel
  (let [repo (fake-repo-participacao {:protocolar {:id (random-uuid) :protocolo "ESIC-2026-000001" :recibo-em t0}})
        r    (pt/response-for (service-fn #{} repo) :post "/portal/esic/pedidos"
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:assunto "Contratos 2026"
                                                                 :descricao "Solicito a lista de contratos."}))
        body (ler-json r)]
    (is (= 201 (:status r)) "cidadao SEM papel protocola -> 201 (LAI: qualquer um pede)")
    (is (= "ESIC-2026-000001" (:protocolo body)) "recibo carrega o protocolo")
    (is (= "2026-07-03T12:00:00Z" (:recibo-em body)) "recibo instantaneo (marco do relogio)")
    (is (not (contains? body :id)) "id interno NAO vaza no recibo")))

(deftest protocolar-sem-token-401
  (let [repo (fake-repo-participacao {:protocolar {:protocolo "x" :recibo-em t0}})
        r    (pt/response-for (service-fn #{} repo) :post "/portal/esic/pedidos"
                              :headers {"content-type" "application/json"}
                              :body (json/write-value-as-string {:assunto "a" :descricao "b"}))]
    (is (= 401 (:status r)) "a rota do cidadao herda a cadeia de auth: sem token -> 401 (fail-closed)")))

(deftest protocolar-corpo-invalido-400
  (let [repo (fake-repo-participacao {:protocolar {:protocolo "x" :recibo-em t0}})
        r    (pt/response-for (service-fn #{} repo) :post "/portal/esic/pedidos"
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:assunto "so assunto"}))]  ; falta descricao
    (is (= 400 (:status r)) "corpo sem descricao -> 400 fail-closed (adapters/in)")))

;; ---------- GET /portal/:ente/esic/acompanhar/:protocolo (PUBLICA, sem auth) ----------

(deftest acompanhar-publico-200-sem-pii
  (let [repo (fake-repo-participacao
              {:acompanhar {:pedido {:id (random-uuid) :protocolo "ESIC-2026-000007" :estado "protocolado"
                                     :assunto "ASSUNTO SIGILOSO" :descricao "TEXTO PII"
                                     :solicitante-identidade-id (random-uuid)}
                            :prazo {:vence-em vence}}})
        r    (pt/response-for (service-fn #{} repo)
                              :get (str "/portal/casa/" (random-uuid) "/esic/acompanhar/ESIC-2026-000007"))
        body (ler-json r)]
    (is (= 200 (:status r)) "rota publica SEM auth -> 200 (ente resolvido do path; RLS isola)")
    (is (= "ESIC-2026-000007" (:protocolo body)))
    (is (= "protocolado" (:estado body)))
    (is (= 20 (:dias-restantes body)) "dias-restantes = vence - hoje (relogio fixo)")
    (is (not (contains? body :assunto)) "assunto (PII) NAO vaza no view publico")
    (is (not (contains? body :descricao)) "descricao (PII) NAO vaza")
    (is (not (contains? body :solicitante-identidade-id)) "solicitante (PII) NAO vaza")
    (is (not (contains? body :id)) "id interno do pedido NAO vaza")
    (is (not (contains? body :ente-id)) "tenant NAO vaza")))

(deftest acompanhar-ente-malformado-400
  (let [repo (fake-repo-participacao {:acompanhar nil})
        r    (pt/response-for (service-fn #{} repo)
                              :get "/portal/casa/nao-e-uuid/esic/acompanhar/ESIC-2026-000001")]
    (is (= 400 (:status r)) ":ente malformado -> 400 fail-closed (NUNCA vaza cross-tenant nem 500)")))

(deftest acompanhar-inexistente-404
  (let [repo (fake-repo-participacao {:acompanhar nil})
        r    (pt/response-for (service-fn #{} repo)
                              :get (str "/portal/casa/" (random-uuid) "/esic/acompanhar/ESIC-2026-999999"))]
    (is (= 404 (:status r)) "protocolo inexistente no tenant -> 404")))

;; ---------- GET /portal/esic/pedidos/:id (dono: auth + policy FINA) ----------

(deftest meu-pedido-nao-dono-403
  (let [dono (random-uuid) intruso (random-uuid) pid (random-uuid)
        repo (fake-repo-participacao
              {:pedido-com-prazo {:pedido {:id pid :protocolo "ESIC-2026-000009" :estado "protocolado"
                                           :assunto "a" :descricao "b" :solicitante-identidade-id dono :recibo-em t0}
                                  :prazo {:vence-em vence}}})
        r    (pt/response-for (service-fn #{} repo) :get (str "/portal/esic/pedidos/" pid)
                              :headers (com-bearer (token (random-uuid) intruso)))]
    (is (= 403 (:status r)) "ator != solicitante -> policy FINA nega (403), mesmo autenticado")))

(deftest meu-pedido-id-malformado-400
  (let [repo (fake-repo-participacao {:pedido-com-prazo nil})
        r    (pt/response-for (service-fn #{} repo) :get "/portal/esic/pedidos/nao-e-uuid"
                              :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 400 (:status r)) ":id malformado -> 400 fail-closed (id-param->uuid), nunca 500")))

(deftest meu-pedido-dono-200-sem-pii-de-terceiro
  (let [dono (random-uuid) pid (random-uuid)
        repo (fake-repo-participacao
              {:pedido-com-prazo {:pedido {:id pid :protocolo "ESIC-2026-000009" :estado "protocolado"
                                           :assunto "meu assunto" :descricao "meu texto"
                                           :solicitante-identidade-id dono :recibo-em t0}
                                  :prazo {:vence-em vence}}})
        r    (pt/response-for (service-fn #{} repo) :get (str "/portal/esic/pedidos/" pid)
                              :headers (com-bearer (token (random-uuid) dono)))
        body (ler-json r)]
    (is (= 200 (:status r)) "o dono le o proprio pedido")
    (is (= "ESIC-2026-000009" (:protocolo body)))
    (is (= 20 (:dias-restantes body)))
    (is (not (contains? body :solicitante-identidade-id)) "id do solicitante NAO vaza no corpo")
    (is (not (contains? body :ente-id)) "tenant NAO vaza")))
