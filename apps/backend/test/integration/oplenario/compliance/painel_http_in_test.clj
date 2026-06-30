(ns oplenario.compliance.painel-http-in-test
  "F5.5a (borda HTTP do compliance) — a vertical de rota do PAINEL 'a Casa esta em dia com o TCE' (§16.11):
  prova a silhueta de borda end-to-end (controller -> repo -> adapters/out -> wire/out) + a authz grossa
  (exige-papel) + 401. DB-free: RepoCompliance FAKE (reify) + idp-dev real (precedente sessoes/http-in-test)."
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
  (:import (java.time Instant LocalDate)))

(defn- fake-repo-compliance
  "RepoCompliance fake: `painel` devolve `resultado`. Impl parcial proposital (so o metodo exercido)."
  [resultado]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-compliance/RepoCompliance
    (painel [_ _ente-id _opts] resultado)))

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
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(defn- painel-canonico [ente]
  {:resumo [{:estado "pendente" :total 1} {:estado "vencida" :total 2}]   ; cumprida/dispensada/cancelada ausentes
   :em-aberto [{:id (random-uuid) :ente-id ente :template-chave "remessa_mensal_sim" :objeto-tipo "competencia"
                :objeto-id (random-uuid) :vence-em (LocalDate/of 2099 7 31) :prazo-fonte-ref "IN 04/2019"
                :estado "vencida" :cumprida-em nil :criado-em (Instant/now) :atualizado-em (Instant/now)}]
   :remessas-recentes [{:id (random-uuid) :ente-id ente :template-chave "remessa_mensal_sim" :sistema "SIM"
                        :competencia "2099-07" :versao 1 :spec-layout-versao "fixture-sim-v0"
                        :registry-versao-ref "registry-v1@2026-06-20" :hash "sha256:abc"
                        :objeto-store-ref "remessas/ente/x.bin" :estado "submetida"
                        :submetida-em (Instant/now) :resposta-em nil :criado-em (Instant/now)}]})

;; ---------- GET /compliance/painel ----------

(deftest painel-200
  (let [ente (random-uuid)
        r (pt/response-for (service-fn #{"secretario"} (fake-repo-compliance (painel-canonico ente)))
                           :get "/compliance/painel" :headers (com-bearer (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)) "GET /compliance/painel com papel secretario -> 200")
    (is (= {:pendente 1 :vencida 2 :cumprida 0 :dispensada 0 :cancelada 0} (:resumo body))
        "o placar 0-fila as 5 fases (normalizacao na borda); os pares crus do Repo viram o mapa completo")
    (is (= 1 (count (:em-aberto body))) "a obrigacao em aberto projetada")
    (let [o (first (:em-aberto body))]
      (is (= "2099-07-31" (:vence-em o)) "vence-em projetado como string ISO de data")
      (is (string? (:objeto-id o)) "objeto-id como string")
      (is (not (contains? o :ente-id)) "ente-id (tenant) nao vaza"))
    (is (= 1 (count (:remessas-recentes body))) "a remessa recente projetada")
    (let [m (first (:remessas-recentes body))]
      (is (= "SIM" (:sistema m)))
      (is (= "submetida" (:estado m)))
      (is (= 1 (:versao m)))
      (is (not (contains? m :objeto-store-ref)) "o ponteiro interno do store NAO vaza")
      (is (not (contains? m :hash)) "o hash interno NAO vaza")
      (is (not (contains? m :registry-versao-ref)) "a proveniencia interna NAO vaza"))))

(deftest painel-sem-papel-403
  (let [r (pt/response-for (service-fn #{"vereador"} (fake-repo-compliance (painel-canonico (random-uuid))))
                           :get "/compliance/painel" :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 403 (:status r)) "ator sem papel 'secretario' -> authz grossa nega -> 403")))

(deftest painel-sem-token-401
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-compliance (painel-canonico (random-uuid))))
                           :get "/compliance/painel")]
    (is (= 401 (:status r)) "rota de modulo herda a cadeia de auth: sem token -> 401 (fail-closed)")))
