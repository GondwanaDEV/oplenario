(ns oplenario.fluxo-ouvidoria-test
  "E2E da BORDA HTTP da ouvidoria (FAST-FOLLOW Slice 5, Lei 13.460/2017 art. 10) — a vertical de rota
  ponta-a-ponta (adapters/in -> controller -> repo -> adapters/out -> wire/out) + os perfis de authz. DB-free:
  RepoParticipacao FAKE (reify) + idp-dev real (precedente compliance/painel_http_in_test; fluxo-esic-test).
  Relogio FIXO injetado no fragmento de rotas (determinismo do dias-restantes). Foco de seguranca: manifestacao
  ANONIMA nao e' sem-auth (a rota exige token como qualquer escrita do cidadao) e o GET de detalhe devolve 404
  p/ anonima (nunca 403 — nao ha dono p/ comparar, nem para o proprio autor); a rota publica de acompanhar
  nao vaza PII e o :ente malformado fail-closa (400)."
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

;; relogio fixo: 12:00Z de 2026-07-03 -> zona civil America/Fortaleza = 2026-07-03; vence (fixture) 2026-08-02
;; -> dias-restantes = 30.
(def ^:private t0 (Instant/parse "2026-07-03T12:00:00Z"))
(def ^:private relogio (tempo/relogio-fixo t0))
(def ^:private vence (LocalDate/of 2026 8 2))

(defn- fake-repo-participacao
  [{:keys [protocolar-manifestacao acompanhar-manifestacao manifestacao-com-prazo buscar-manifestacao
           responder-manifestacao arquivar-manifestacao prazo-do-objeto prorrogar-manifestacao]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-part/RepoParticipacao
    (protocolar-manifestacao! [_ _ente _m] protocolar-manifestacao)
    (acompanhar-manifestacao-por-protocolo [_ _ente _protocolo] acompanhar-manifestacao)
    (manifestacao-com-prazo [_ _ente _id] manifestacao-com-prazo)
    (buscar-manifestacao [_ _ente _id] buscar-manifestacao)
    (responder-manifestacao! [_ _ente _m] responder-manifestacao)
    (arquivar-manifestacao! [_ _ente _m] arquivar-manifestacao)
    (prazo-do-objeto [_ _ente _tipo _id] prazo-do-objeto)
    (prorrogar-manifestacao! [_ _ente _m] prorrogar-manifestacao)))

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

;; ---------- POST /portal/ouvidoria/manifestacoes (cidadao: SO auth, anonima != sem-auth) ----------

(deftest protocolar-201-cidadao-nao-anonima
  (let [repo (fake-repo-participacao {:protocolar-manifestacao {:id (random-uuid) :protocolo "OUV-2026-000001" :recibo-em t0}})
        r    (pt/response-for (service-fn #{} repo) :post "/portal/ouvidoria/manifestacoes"
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:tipo "reclamacao" :assunto "Buracos"
                                                                 :descricao "A rua X esta cheia de buracos."}))
        body (ler-json r)]
    (is (= 201 (:status r)) "cidadao autenticado protocola -> 201")
    (is (= "OUV-2026-000001" (:protocolo body)))
    (is (= "2026-07-03T12:00:00Z" (:recibo-em body)))
    (is (not (contains? body :id)) "id interno NAO vaza no recibo")))

(deftest protocolar-anonima-AINDA-exige-auth-401-sem-token
  (let [repo (fake-repo-participacao {:protocolar-manifestacao {:protocolo "x" :recibo-em t0}})
        r    (pt/response-for (service-fn #{} repo) :post "/portal/ouvidoria/manifestacoes"
                              :headers {"content-type" "application/json"}
                              :body (json/write-value-as-string {:tipo "denuncia" :assunto "a" :descricao "b" :anonima true}))]
    (is (= 401 (:status r))
        "DECISAO DE ARQUITETURA: anonima NAO e' sem-auth — a escrita SEMPRE exige token, mesmo p/ manifestacao anonima")))

(deftest protocolar-anonima-201-com-auth
  (let [repo (fake-repo-participacao {:protocolar-manifestacao {:protocolo "OUV-2026-000002" :recibo-em t0}})
        r    (pt/response-for (service-fn #{} repo) :post "/portal/ouvidoria/manifestacoes"
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:tipo "denuncia" :assunto "a" :descricao "b" :anonima true}))]
    (is (= 201 (:status r)) "anonima COM token -> 201 (so' nao persiste o manifestante, ver integracao)")))

(deftest protocolar-corpo-invalido-400
  (let [repo (fake-repo-participacao {:protocolar-manifestacao {:protocolo "x" :recibo-em t0}})
        r    (pt/response-for (service-fn #{} repo) :post "/portal/ouvidoria/manifestacoes"
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:tipo "reclamacao" :assunto "so assunto"}))]  ; falta descricao
    (is (= 400 (:status r)) "corpo sem descricao -> 400 fail-closed")))

(deftest protocolar-tipo-invalido-400
  (let [repo (fake-repo-participacao {:protocolar-manifestacao {:protocolo "x" :recibo-em t0}})
        r    (pt/response-for (service-fn #{} repo) :post "/portal/ouvidoria/manifestacoes"
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:tipo "critica" :assunto "a" :descricao "b"}))]
    (is (= 400 (:status r)) "tipo fora do enum dos 5 padrao CGU -> 400 fail-closed")))

;; ---------- GET /portal/ouvidoria/manifestacoes/:id (dono: 404 se anonima, senao policy fina) ----------

(deftest minha-manifestacao-anonima-404-mesmo-para-o-proprio-autor
  (let [autor (random-uuid) mid (random-uuid)
        repo (fake-repo-participacao
              {:manifestacao-com-prazo {:manifestacao {:id mid :protocolo "OUV-2026-000003" :estado "protocolada"
                                                       :tipo "denuncia" :assunto "a" :descricao "b" :anonima true
                                                       :manifestante-identidade-id nil :recibo-em t0}
                                        :prazo {:vence-em vence}}})
        r    (pt/response-for (service-fn #{} repo) :get (str "/portal/ouvidoria/manifestacoes/" mid)
                              :headers (com-bearer (token (random-uuid) autor)))]
    (is (= 404 (:status r))
        "ANONIMA: 404 mesmo pro proprio autor (nao ha dono persistido p/ comparar) — nao 403")))

(deftest minha-manifestacao-nao-dono-403
  (let [dono (random-uuid) intruso (random-uuid) mid (random-uuid)
        repo (fake-repo-participacao
              {:manifestacao-com-prazo {:manifestacao {:id mid :protocolo "OUV-2026-000004" :estado "protocolada"
                                                       :tipo "reclamacao" :assunto "a" :descricao "b" :anonima false
                                                       :manifestante-identidade-id dono :recibo-em t0}
                                        :prazo {:vence-em vence}}})
        r    (pt/response-for (service-fn #{} repo) :get (str "/portal/ouvidoria/manifestacoes/" mid)
                              :headers (com-bearer (token (random-uuid) intruso)))]
    (is (= 403 (:status r)) "NAO-anonima: ator != manifestante -> policy FINA nega (403)")))

(deftest minha-manifestacao-dono-200-sem-pii-de-terceiro
  (let [dono (random-uuid) mid (random-uuid)
        repo (fake-repo-participacao
              {:manifestacao-com-prazo {:manifestacao {:id mid :protocolo "OUV-2026-000005" :estado "protocolada"
                                                       :tipo "elogio" :assunto "a" :descricao "b" :anonima false
                                                       :manifestante-identidade-id dono :recibo-em t0}
                                        :prazo {:vence-em vence}}})
        r    (pt/response-for (service-fn #{} repo) :get (str "/portal/ouvidoria/manifestacoes/" mid)
                              :headers (com-bearer (token (random-uuid) dono)))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= "OUV-2026-000005" (:protocolo body)))
    (is (= 30 (:dias-restantes body)))
    (is (not (contains? body :manifestante-identidade-id)))))

;; ---------- GET /portal/casa/:ente/ouvidoria/acompanhar/:protocolo (PUBLICA, sem auth) ----------

(deftest acompanhar-publico-200-sem-pii
  (let [repo (fake-repo-participacao
              {:acompanhar-manifestacao {:manifestacao {:id (random-uuid) :protocolo "OUV-2026-000006" :estado "protocolada"
                                                        :tipo "sugestao" :assunto "SIGILOSO" :descricao "PII"
                                                        :anonima false :manifestante-identidade-id (random-uuid)}
                                         :prazo {:vence-em vence}}})
        r    (pt/response-for (service-fn #{} repo)
                              :get (str "/portal/casa/" (random-uuid) "/ouvidoria/acompanhar/OUV-2026-000006"))
        body (ler-json r)]
    (is (= 200 (:status r)) "rota publica SEM auth -> 200")
    (is (= "OUV-2026-000006" (:protocolo body)))
    (is (= 30 (:dias-restantes body)))
    (is (not (contains? body :assunto)))
    (is (not (contains? body :descricao)))
    (is (not (contains? body :manifestante-identidade-id)))
    (is (not (contains? body :id)))))

(deftest acompanhar-ente-malformado-400
  (let [repo (fake-repo-participacao {:acompanhar-manifestacao nil})
        r    (pt/response-for (service-fn #{} repo)
                              :get "/portal/casa/nao-e-uuid/ouvidoria/acompanhar/OUV-2026-000001")]
    (is (= 400 (:status r)) ":ente malformado -> 400 fail-closed")))

(deftest acompanhar-inexistente-404
  (let [repo (fake-repo-participacao {:acompanhar-manifestacao nil})
        r    (pt/response-for (service-fn #{} repo)
                              :get (str "/portal/casa/" (random-uuid) "/ouvidoria/acompanhar/OUV-2026-999999"))]
    (is (= 404 (:status r)))))

;; ---------- POST /ouvidoria/manifestacoes/:id/resposta (SERVIDOR) ----------

(deftest responder-servidor-200
  (let [mid  (random-uuid)
        repo (fake-repo-participacao {:responder-manifestacao {:respondida-em t0 :protocolo "OUV-2026-000001"}})
        r    (pt/response-for (service-fn #{"secretario"} repo) :post (str "/ouvidoria/manifestacoes/" mid "/resposta")
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:corpo "Encaminhado."}))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= "2026-07-03T12:00:00Z" (:respondida-em body)))))

(deftest responder-sem-papel-403
  (let [mid  (random-uuid)
        repo (fake-repo-participacao {:responder-manifestacao {:respondida-em t0}})
        r    (pt/response-for (service-fn #{} repo) :post (str "/ouvidoria/manifestacoes/" mid "/resposta")
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:corpo "x"}))]
    (is (= 403 (:status r)))))

(deftest responder-ja-terminal-409
  (let [mid  (random-uuid)
        repo (fake-repo-participacao {:responder-manifestacao nil :buscar-manifestacao {:id mid :estado "respondida"}})
        r    (pt/response-for (service-fn #{"secretario"} repo) :post (str "/ouvidoria/manifestacoes/" mid "/resposta")
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:corpo "x"}))]
    (is (= 409 (:status r)))))

;; ---------- POST /ouvidoria/manifestacoes/:id/arquivar (SERVIDOR) ----------

(deftest arquivar-servidor-200
  (let [mid  (random-uuid)
        repo (fake-repo-participacao {:arquivar-manifestacao {:arquivada-em t0 :protocolo "OUV-2026-000001"}})
        r    (pt/response-for (service-fn #{"secretario"} repo) :post (str "/ouvidoria/manifestacoes/" mid "/arquivar")
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:motivo "Duplicada."}))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= "2026-07-03T12:00:00Z" (:arquivada-em body)))))

(deftest arquivar-motivo-vazio-400
  (let [mid  (random-uuid)
        repo (fake-repo-participacao {:arquivar-manifestacao {:arquivada-em t0}})
        r    (pt/response-for (service-fn #{"secretario"} repo) :post (str "/ouvidoria/manifestacoes/" mid "/arquivar")
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:motivo "   "}))]
    (is (= 400 (:status r)) "motivo obrigatorio, em branco -> 400 fail-closed")))

(deftest arquivar-sem-papel-403
  (let [mid  (random-uuid)
        repo (fake-repo-participacao {:arquivar-manifestacao {:arquivada-em t0}})
        r    (pt/response-for (service-fn #{} repo) :post (str "/ouvidoria/manifestacoes/" mid "/arquivar")
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:motivo "x"}))]
    (is (= 403 (:status r)))))

;; ---------- POST /ouvidoria/manifestacoes/:id/prorrogar (SERVIDOR) ----------

(deftest prorrogar-servidor-200
  (let [mid  (random-uuid)
        repo (fake-repo-participacao {:prazo-do-objeto {:vence-em vence}
                                      :prorrogar-manifestacao {:prorrogado-ate (LocalDate/of 2026 9 1)}})
        r    (pt/response-for (service-fn #{"secretario"} repo) :post (str "/ouvidoria/manifestacoes/" mid "/prorrogar")
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:justificativa "Apuracao em curso."}))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= "2026-09-01" (:prorrogado-ate body)))))

(deftest prorrogar-manifestacao-inexistente-404
  (let [mid  (random-uuid)
        repo (fake-repo-participacao {:prazo-do-objeto nil})
        r    (pt/response-for (service-fn #{"secretario"} repo) :post (str "/ouvidoria/manifestacoes/" mid "/prorrogar")
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:justificativa "x"}))]
    (is (= 404 (:status r)))))

(deftest prorrogar-ja-prorrogada-409
  (let [mid  (random-uuid)
        repo (fake-repo-participacao {:prazo-do-objeto {:vence-em vence} :prorrogar-manifestacao nil})
        r    (pt/response-for (service-fn #{"secretario"} repo) :post (str "/ouvidoria/manifestacoes/" mid "/prorrogar")
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:justificativa "2a tentativa"}))]
    (is (= 409 (:status r)))))

(deftest prorrogar-justificativa-vazia-400
  (let [mid  (random-uuid)
        repo (fake-repo-participacao {:prazo-do-objeto {:vence-em vence}})
        r    (pt/response-for (service-fn #{"secretario"} repo) :post (str "/ouvidoria/manifestacoes/" mid "/prorrogar")
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:justificativa "   "}))]
    (is (= 400 (:status r)))))

(deftest prorrogar-sem-papel-403
  (let [mid  (random-uuid)
        repo (fake-repo-participacao {:prazo-do-objeto {:vence-em vence}})
        r    (pt/response-for (service-fn #{} repo) :post (str "/ouvidoria/manifestacoes/" mid "/prorrogar")
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:justificativa "x"}))]
    (is (= 403 (:status r)))))
