(ns oplenario.compliance.remessa-ciclo-http-in-test
  "F5.5b (borda HTTP do compliance) — a vertical de rota do CICLO DA REMESSA (validar -> submeter ->
  registrar-resposta do TCE). Prova a silhueta de borda end-to-end (adapters/in -> controller -> repo ->
  adapters/out -> wire/out) + a authz grossa (exige-papel) + os codigos de erro: corpo/uuid invalido -> 400,
  CAS perdido por estado incompativel -> 409, remessa inexistente -> 404, sem papel -> 403, sem token -> 401.
  DB-free: RepoCompliance FAKE (reify) + idp-dev real (precedente sessoes/http-in-test + painel-http-in-test)."
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.compliance.components.repositorio :as repo-compliance]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.rotas :as rotas])
  (:import (java.time Instant)))

(defn- remessa-canonica [ente estado]
  {:id (random-uuid) :ente-id ente :template-chave "remessa_mensal_sim" :sistema "SIM"
   :competencia "2099-07" :versao 1 :spec-layout-versao "fixture-sim-v0"
   :registry-versao-ref "registry-v1@2026-06-20" :hash "sha256:abc"
   :objeto-store-ref "remessas/ente/x.bin" :estado estado
   :submetida-em (when (#{"submetida" "aceita" "rejeitada"} estado) (Instant/now))
   :resposta-em (when (#{"aceita" "rejeitada"} estado) (Instant/now))
   :criado-em (Instant/now)})

(defn- fake-repo-compliance
  "RepoCompliance fake parametrizado por mapa de comportamento:
   {:validar (fn []->row|nil) :submeter (fn []->row|nil) :resposta (fn [estado]->row|nil)
    :existe (fn []->boolean) — desambigua o nil (CAS perdido vs inexistente)
    :ente-visto (atom) — opcional: captura o ente-id que o controller passa ao Repo (prova de tenant)}.
   Impl parcial proposital (so os metodos exercidos)."
  [{:keys [validar submeter resposta existe ente-visto]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-compliance/RepoCompliance
    (validar-remessa! [_ ente _id] (when ente-visto (reset! ente-visto ente)) (when validar (validar)))
    (submeter-remessa! [_ _ente _id] (when submeter (submeter)))
    (registrar-resposta-remessa! [_ _ente _id estado] (when resposta (resposta estado)))
    (remessa-existe? [_ _ente _id] (boolean (when existe (existe))))))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- service-fn [papeis repo-c]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-compliance repo-c})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id]
  (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))

(defn- com-bearer [tok] {"authorization" (str "Bearer " tok)})
(defn- com-bearer-json [tok] (assoc (com-bearer tok) "Content-Type" "application/json"))
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

;; ---------- POST /compliance/remessas/:id/validar ----------

(deftest validar-200-e-projeta-sem-vazar-interno
  (let [ente (random-uuid) id (random-uuid)
        ente-visto (atom nil)
        repo (fake-repo-compliance {:validar #(remessa-canonica ente "validada") :ente-visto ente-visto})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post (str "/compliance/remessas/" id "/validar")
                           :headers (com-bearer (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)) "validar com papel secretario -> 200")
    (is (= ente @ente-visto) "o ente-id que chega ao Repo e' o do ATOR (token), nunca do cliente/path")
    (is (= "validada" (:estado body)) "projeta o estado novo")
    (is (string? (:id body)) "id como string")
    (is (not (contains? body :ente-id)) "ente-id (tenant) NAO vaza")
    (is (not (contains? body :objeto-store-ref)) "o ponteiro interno do store NAO vaza")
    (is (not (contains? body :hash)) "o hash interno NAO vaza")
    (is (not (contains? body :registry-versao-ref)) "a proveniencia interna NAO vaza")
    (is (not (contains? body :spec-layout-versao)) "a versao do layout (interno) NAO vaza")))

(deftest validar-conflito-409
  (let [ente (random-uuid) id (random-uuid)
        ;; CAS perdido (estado != rascunho): transicao devolve nil, mas a remessa EXISTE no tenant.
        repo (fake-repo-compliance {:validar (constantly nil)
                                    :existe (constantly true)})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post (str "/compliance/remessas/" id "/validar")
                           :headers (com-bearer (token ente (random-uuid))))]
    (is (= 409 (:status r)) "remessa em estado incompativel com a transicao -> 409 (nao 500)")))

(deftest validar-inexistente-404
  (let [ente (random-uuid) id (random-uuid)
        ;; transicao nil + existe false = remessa nao existe no tenant.
        repo (fake-repo-compliance {:validar (constantly nil) :existe (constantly false)})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post (str "/compliance/remessas/" id "/validar")
                           :headers (com-bearer (token ente (random-uuid))))]
    (is (= 404 (:status r)) "remessa inexistente -> 404")))

(deftest validar-id-malformado-400
  (let [repo (fake-repo-compliance {:validar #(remessa-canonica (random-uuid) "validada")})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post "/compliance/remessas/nao-e-uuid/validar"
                           :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 400 (:status r)) "id de path malformado -> 400 (validacao de borda, nunca 500)")))

;; ---------- POST /compliance/remessas/:id/submeter ----------

(deftest submeter-200
  (let [ente (random-uuid) id (random-uuid)
        repo (fake-repo-compliance {:submeter #(remessa-canonica ente "submetida")})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post (str "/compliance/remessas/" id "/submeter")
                           :headers (com-bearer (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)) "submeter -> 200")
    (is (= "submetida" (:estado body)) "projeta submetida")
    (is (string? (:submetida-em body)) "submetida-em projetado como string ISO")))

(deftest submeter-conflito-409
  (let [ente (random-uuid) id (random-uuid)
        repo (fake-repo-compliance {:submeter (constantly nil)
                                    :existe (constantly true)})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post (str "/compliance/remessas/" id "/submeter")
                           :headers (com-bearer (token ente (random-uuid))))]
    (is (= 409 (:status r)) "submeter remessa que nao esta em validada -> 409")))

;; ---------- POST /compliance/remessas/:id/resposta ----------

(deftest resposta-aceita-200
  (let [ente (random-uuid) id (random-uuid)
        repo (fake-repo-compliance {:resposta (fn [estado] (remessa-canonica ente estado))})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post (str "/compliance/remessas/" id "/resposta")
                           :headers (com-bearer-json (token ente (random-uuid)))
                           :body (json/write-value-as-string {"estado" "aceita"}))
        body (ler-json r)]
    (is (= 200 (:status r)) "registrar resposta 'aceita' -> 200")
    (is (= "aceita" (:estado body)) "projeta aceita (a costura remessa_enviada cumpre a obrigacao)")
    (is (string? (:resposta-em body)) "resposta-em projetado como string ISO")))

(deftest resposta-rejeitada-200
  (let [ente (random-uuid) id (random-uuid)
        repo (fake-repo-compliance {:resposta (fn [estado] (remessa-canonica ente estado))})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post (str "/compliance/remessas/" id "/resposta")
                           :headers (com-bearer-json (token ente (random-uuid)))
                           :body (json/write-value-as-string {"estado" "rejeitada"}))]
    (is (= 200 (:status r)) "registrar resposta 'rejeitada' -> 200")
    (is (= "rejeitada" (:estado (ler-json r))) "projeta rejeitada (NAO cumpre; reenvio = nova versao)")))

(deftest resposta-estado-invalido-400
  (let [ente (random-uuid) id (random-uuid)
        repo (fake-repo-compliance {:resposta (fn [estado] (remessa-canonica ente estado))})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post (str "/compliance/remessas/" id "/resposta")
                           :headers (com-bearer-json (token ente (random-uuid)))
                           :body (json/write-value-as-string {"estado" "submetida"}))]
    (is (= 400 (:status r)) "estado != aceita|rejeitada barra na borda -> 400 (nao chega ao Repo)")))

(deftest resposta-sem-corpo-400
  (let [ente (random-uuid) id (random-uuid)
        repo (fake-repo-compliance {:resposta (fn [estado] (remessa-canonica ente estado))})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post (str "/compliance/remessas/" id "/resposta")
                           :headers (com-bearer-json (token ente (random-uuid))))]
    (is (= 400 (:status r)) "resposta sem {estado} -> 400")))

(deftest resposta-conflito-409
  (let [ente (random-uuid) id (random-uuid)
        repo (fake-repo-compliance {:resposta (constantly nil)
                                    :existe (constantly true)})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post (str "/compliance/remessas/" id "/resposta")
                           :headers (com-bearer-json (token ente (random-uuid)))
                           :body (json/write-value-as-string {"estado" "aceita"}))]
    (is (= 409 (:status r)) "registrar resposta de remessa que nao esta em submetida -> 409")))

;; ---------- authz: a vertical herda a cadeia de auth/papel ----------

(deftest sem-papel-403
  (let [ente (random-uuid) id (random-uuid)
        repo (fake-repo-compliance {:validar #(remessa-canonica ente "validada")})
        r (pt/response-for (service-fn #{"vereador"} repo)
                           :post (str "/compliance/remessas/" id "/validar")
                           :headers (com-bearer (token ente (random-uuid))))]
    (is (= 403 (:status r)) "ator sem papel 'secretario' -> authz grossa nega -> 403")))

(deftest sem-token-401
  (let [id (random-uuid)
        repo (fake-repo-compliance {:validar #(remessa-canonica (random-uuid) "validada")})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post (str "/compliance/remessas/" id "/validar"))]
    (is (= 401 (:status r)) "rota de modulo herda a cadeia de auth: sem token -> 401 (fail-closed)")))
