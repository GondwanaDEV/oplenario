(ns oplenario.paineis.sli-sessao-http-in-test
  "F7 E3 (borda HTTP do paineis) — a vertical de rota do SLI de janela de sessao (Inv.9): prova a silhueta de
  borda end-to-end (controller -> repo -> adapters/out -> wire/out), a DERIVACAO (situacao/duracao), a authz
  grossa (exige-papel) + 401. DB-free: RepoPaineis FAKE (reify) + idp-dev real (precedente
  tramitacao-http-in-test)."
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.paineis.components.repositorio :as repo-paineis]
            [oplenario.rotas :as rotas])
  (:import (java.time Instant)))

(defn- fake-repo-paineis [resultado]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-paineis/RepoPaineis
    (sli-sessoes [_ _ente-id] resultado)))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- service-fn [papeis repo-p]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-paineis repo-p})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id]
  (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))

(defn- com-bearer [tok] {"authorization" (str "Bearer " tok)})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(defn- sessao-encerrada []
  {:sessao-id (random-uuid) :estado-atual "encerrada"
   :aberta-em (Instant/parse "2026-07-01T13:00:00Z")
   :encerrada-em (Instant/parse "2026-07-01T15:30:00Z")})   ; 2h30 = 9000s

(defn- sessao-em-curso []
  {:sessao-id (random-uuid) :estado-atual "aberta"
   :aberta-em (Instant/parse "2026-07-01T13:00:00Z") :encerrada-em nil})

;; ---------- GET /paineis/sli/sessoes ----------

(deftest sli-200-deriva-situacao-e-duracao
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-paineis [(sessao-encerrada)]))
                           :get "/paineis/sli/sessoes" :headers (com-bearer (token (random-uuid) (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)) "GET /paineis/sli/sessoes com papel secretario -> 200")
    (is (= 1 (count (:sessoes body))))
    (let [s (first (:sessoes body))]
      (is (= "realizada" (:situacao s)) "encerrada -> situacao derivada 'realizada'")
      (is (= 9000 (:duracao-segundos s)) "janela fechada em segundos (2h30)")
      (is (string? (:sessao-id s)) "sessao-id como string")
      (is (not (contains? s :ente-id)) "ente-id (tenant) nao vaza"))))

(deftest sli-em-curso-sem-duracao
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-paineis [(sessao-em-curso)]))
                           :get "/paineis/sli/sessoes" :headers (com-bearer (token (random-uuid) (random-uuid))))
        s (first (:sessoes (ler-json r)))]
    (is (= 200 (:status r)))
    (is (= "em_curso" (:situacao s)) "aberta -> 'em_curso'")
    (is (nil? (:duracao-segundos s)) "sessao em curso nao tem janela fechada (duracao viva e' do cliente)")))

(deftest sli-vazio-200
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-paineis []))
                           :get "/paineis/sli/sessoes" :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 200 (:status r)))
    (is (= [] (:sessoes (ler-json r))))))

(deftest sli-sem-papel-403
  (let [r (pt/response-for (service-fn #{"vereador"} (fake-repo-paineis []))
                           :get "/paineis/sli/sessoes" :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 403 (:status r)) "ator sem papel 'secretario' -> authz grossa nega -> 403")))

(deftest sli-sem-token-401
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-paineis []))
                           :get "/paineis/sli/sessoes")]
    (is (= 401 (:status r)) "rota de modulo herda a cadeia de auth: sem token -> 401 (fail-closed)")))
