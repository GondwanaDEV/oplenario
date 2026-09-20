(ns oplenario.paineis.pendencias-http-in-test
  "F7 Slice 1 (borda HTTP do paineis) — a vertical de rota do painel 'o que vence' (§16.11): prova a
  silhueta de borda end-to-end (controller -> repo -> adapters/out -> wire/out) + a authz grossa
  (exige-papel) + 401. DB-free: RepoPaineis FAKE (reify) + idp-dev real (precedente compliance/
  painel-http-in-test)."
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
  (:import (java.time LocalDate)))

(defn- fake-repo-paineis
  "RepoPaineis fake: `o-que-vence` devolve `resultado`. Impl parcial proposital (so o metodo exercido)."
  [resultado]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-paineis/RepoPaineis
    (o-que-vence [_ _ente-id _opts] resultado)))

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

(defn- pendencia-canonica [ente]
  {:ente-id ente :objeto-tipo "pedido_esic" :objeto-id (random-uuid) :protocolo "ESIC-2026-000001"
   :vence-em (LocalDate/of 2099 7 31) :estado "pendente"
   :projetado-em (java.time.Instant/now) :atualizado-em (java.time.Instant/now)})

;; ---------- GET /paineis/pendencias ----------

(deftest pendencias-200
  (let [ente (random-uuid)
        r (pt/response-for (service-fn #{"secretario"}
                              (fake-repo-paineis {:pendencias [(pendencia-canonica ente)] :pendencias-total 1}))
                           :get "/paineis/pendencias" :headers (com-bearer (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)) "GET /paineis/pendencias com papel secretario -> 200")
    (is (= 1 (count (:pendencias body))))
    (let [p (first (:pendencias body))]
      (is (= "pedido_esic" (:objeto-tipo p)))
      (is (= "ESIC-2026-000001" (:protocolo p)))
      (is (= "2099-07-31" (:vence-em p)) "vence-em projetado como string ISO de data")
      (is (string? (:objeto-id p)) "objeto-id como string")
      (is (= "pendente" (:estado p)))
      (is (not (contains? p :ente-id)) "ente-id (tenant) nao vaza"))))

(deftest pendencias-total-vem-do-repo-verbatim-200
  ;; :pendencias-total DIVERGE DE PROPOSITO da count da lista (4, nao 1) — mesma disciplina de
  ;; compliance/painel-http-in-test/painel-canonico: prova que a rota devolve o TOTAL que o Repo mandou,
  ;; nao um `count` da lista recalculado na borda (o corte silencioso que este total existe para matar
  ;; seria invisivel se o teste usasse o mesmo numero nos dois).
  (let [ente (random-uuid)
        r (pt/response-for (service-fn #{"secretario"}
                              (fake-repo-paineis {:pendencias [(pendencia-canonica ente)] :pendencias-total 4}))
                           :get "/paineis/pendencias" :headers (com-bearer (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= 1 (count (:pendencias body))) "a lista trouxe 1 (recorte da pagina)")
    (is (= 4 (:pendencias-total body))
        "o total vem do Repo verbatim, NAO de (count pendencias) — a rota nao finge completude")))

(deftest pendencias-vazio-200
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-paineis {:pendencias [] :pendencias-total 0}))
                           :get "/paineis/pendencias" :headers (com-bearer (token (random-uuid) (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= [] (:pendencias body)))
    (is (= 0 (:pendencias-total body)))))

(deftest pendencias-papel-sem-leitura-403
  (let [r (pt/response-for (service-fn #{"cidadao"} (fake-repo-paineis {:pendencias [] :pendencias-total 0}))
                           :get "/paineis/pendencias" :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 403 (:status r)) "papel sem leitura (nem secretario nem vereador) -> 403")))

(deftest pendencias-vereador-le-200
  (let [r (pt/response-for (service-fn #{"vereador"} (fake-repo-paineis {:pendencias [] :pendencias-total 0}))
                           :get "/paineis/pendencias" :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 200 (:status r)) "vereador agora LE o painel de pendencias")))

(deftest pendencias-sem-token-401
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-paineis {:pendencias [] :pendencias-total 0}))
                           :get "/paineis/pendencias")]
    (is (= 401 (:status r)) "rota de modulo herda a cadeia de auth: sem token -> 401 (fail-closed)")))
