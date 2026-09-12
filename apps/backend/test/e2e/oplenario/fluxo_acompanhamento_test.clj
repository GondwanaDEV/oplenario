(ns oplenario.fluxo-acompanhamento-test
  "E2E da BORDA HTTP do acompanhamento (F6c Slice 2) — a vertical autenticada ponta-a-ponta. DB-free:
  RepoTransparencia FAKE (reify) + idp-dev real + fake repo-identidade (mesmo precedente de
  fluxo_ouvidoria_test). Foco: o perfil SO-auth (401 sem token), o guard de materia inexistente (404), a
  idempotencia do DELETE (200 sempre) e o escopo pelo ator."
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.transparencia.components.repositorio :as repo-transparencia]
            [oplenario.transparencia.diplomat.http.in :as transparencia-http]))

(defn- fake-repo
  [{:keys [buscar-materia seguir deixar-de-seguir meus]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-transparencia/RepoTransparencia
    (buscar-materia [_ _ente _pid] buscar-materia)
    (seguir! [_ _ente _m] seguir)
    (deixar-de-seguir! [_ _ente _m] deixar-de-seguir)
    (meus-acompanhamentos [_ _ente _sid] meus)))

(defn- fake-repo-identidade []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "cidadao"} :papeis #{}})))

(defn- service-fn [repo]
  (let [auth  (it/autenticacao (idp-dev/idp-dev) (fake-repo-identidade))
        rotas (transparencia-http/rotas {:auth auth :repo-transparencia repo
                                         :resolver-ente-publico transparencia-http/resolver-ente-publico-uuid})]
    (-> (http/servico (config/carregar) rotas it/globais)
        ph/create-server ::ph/service-fn)))

(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))
(defn- token [ente ident] (json/write-value-as-string {:sub "u" :ente-id (str ente) :identidade-id (str ident)}))
(defn- com-bearer [tok] {"authorization" (str "Bearer " tok)})

(def ^:private ente (random-uuid))
(def ^:private pid (random-uuid))

;; ---------- POST /portal/materias/:proposicao_id/acompanhar ----------

(deftest seguir-201-cidadao
  (let [repo (fake-repo {:buscar-materia {:proposicao-id pid} :seguir {:id (random-uuid) :estado "ativo"}})
        r    (pt/response-for (service-fn repo) :post (str "/portal/materias/" pid "/acompanhar")
                              :headers (com-bearer (token ente (random-uuid))))]
    (is (= 201 (:status r)))
    (is (= "ativo" (:estado (ler-json r))))))

(deftest seguir-materia-inexistente-404
  (let [repo (fake-repo {:buscar-materia nil})
        r    (pt/response-for (service-fn repo) :post (str "/portal/materias/" pid "/acompanhar")
                              :headers (com-bearer (token ente (random-uuid))))]
    (is (= 404 (:status r)) "guard: materia ausente -> 404 (nao se segue UUID solto)")))

(deftest seguir-sem-token-401
  (let [repo (fake-repo {:buscar-materia {:proposicao-id pid} :seguir {:estado "ativo"}})
        r    (pt/response-for (service-fn repo) :post (str "/portal/materias/" pid "/acompanhar"))]
    (is (= 401 (:status r)) "escrita SEMPRE exige token")))

(deftest seguir-proposicao-malformada-400
  (let [repo (fake-repo {:buscar-materia {:proposicao-id pid} :seguir {:estado "ativo"}})
        r    (pt/response-for (service-fn repo) :post "/portal/materias/nao-e-uuid/acompanhar"
                              :headers (com-bearer (token ente (random-uuid))))]
    (is (= 400 (:status r)) "proposicao_id malformado -> 400 fail-closed")))

;; ---------- DELETE /portal/materias/:proposicao_id/acompanhar (idempotente) ----------

(deftest deixar-de-seguir-200-mesmo-sem-seguir
  (let [repo (fake-repo {:deixar-de-seguir nil})   ; nil = nao seguia -> ainda 200 (idempotente)
        r    (pt/response-for (service-fn repo) :delete (str "/portal/materias/" pid "/acompanhar")
                              :headers (com-bearer (token ente (random-uuid))))]
    (is (= 200 (:status r)))
    (is (= "cancelado" (:estado (ler-json r))) "DELETE idempotente devolve o estado-alvo")))

(deftest deixar-de-seguir-sem-token-401
  (let [repo (fake-repo {:deixar-de-seguir nil})
        r    (pt/response-for (service-fn repo) :delete (str "/portal/materias/" pid "/acompanhar"))]
    (is (= 401 (:status r)) "DELETE tambem exige token (simetria com o POST)")))

;; ---------- GET /portal/acompanhamentos ----------

(deftest meus-acompanhamentos-200
  (let [repo (fake-repo {:meus {:acompanhamentos
                                 [{:proposicao-id pid :tipo "projeto_lei" :ano 2026 :sequencial 1
                                   :urn-lex "urn:x" :ementa "Dispoe sobre X" :estado "protocolada"
                                   :seguido-em (java.time.Instant/parse "2026-07-03T12:00:00Z")
                                   :indisponivel false}]
                                 :acompanhamentos-total 1}})
        r    (pt/response-for (service-fn repo) :get "/portal/acompanhamentos"
                              :headers (com-bearer (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= 1 (count (:acompanhamentos body))))
    (is (= 1 (:acompanhamentos-total body)))
    (is (= "Dispoe sobre X" (:ementa (first (:acompanhamentos body)))))))

(deftest meus-acompanhamentos-total-diverge-de-proposito-da-count-da-lista-200
  ;; mesma disciplina de listar-materias-total-diverge-de-proposito: a borda repassa o numero do Repo
  ;; VERBATIM, nunca `(count acompanhamentos)`.
  (let [repo (fake-repo {:meus {:acompanhamentos [] :acompanhamentos-total 9}})
        r    (pt/response-for (service-fn repo) :get "/portal/acompanhamentos"
                              :headers (com-bearer (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 9 (:acompanhamentos-total body)) "o total vem do Repo, nao de (count acompanhamentos)")))

(deftest meus-acompanhamentos-sem-token-401
  (let [repo (fake-repo {:meus {:acompanhamentos [] :acompanhamentos-total 0}})
        r    (pt/response-for (service-fn repo) :get "/portal/acompanhamentos")]
    (is (= 401 (:status r)))))
