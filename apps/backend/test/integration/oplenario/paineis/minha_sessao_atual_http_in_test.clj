(ns oplenario.paineis.minha-sessao-atual-http-in-test
  "Onda C3 — GET /meu/sessao-atual: descoberta de sessao viva p/ o cockpit do celular. Reusa a MESMA leitura
  de sli-sessoes (paineis/controllers), so' projeta a PRIMEIRA entrada, sob um gate 'vereador' (nao
  'secretario' — mesmo precedente de /meu/painel e /meu/ciencias no legislativo). DB-free: RepoPaineis FAKE
  (reify) + idp-dev real, mesmo padrao de sli-sessao-http-in-test."
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
      {:vinculo-ativo {:id (random-uuid) :tipo "vereador"} :papeis papeis})))

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

(defn- sessao-em-curso []
  {:sessao-id (random-uuid) :estado-atual "aberta"
   :aberta-em (Instant/parse "2026-07-01T13:00:00Z") :encerrada-em nil})

(defn- sessao-agendada []
  {:sessao-id (random-uuid) :estado-atual "agendada"
   :agendada-para (Instant/parse "2026-08-01T13:00:00Z") :aberta-em nil :encerrada-em nil})

;; ---------- GET /meu/sessao-atual ----------

(deftest minha-sessao-200-com-sessao-viva
  (let [r (pt/response-for (service-fn #{"vereador"} (fake-repo-paineis [(sessao-em-curso)]))
                           :get "/meu/sessao-atual" :headers (com-bearer (token (random-uuid) (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)) "papel vereador + sessao em curso -> 200")
    (is (string? (:sessao-id body)) "sessao-id da PRIMEIRA entrada (ja' ordenada em-curso-primeiro)")
    (is (= "em_curso" (:situacao body)) "situacao DERIVADA do estado-atual cru, mesma logic/situacao do SLI")))

(deftest minha-sessao-200-sem-sessao-viva
  (let [r (pt/response-for (service-fn #{"vereador"} (fake-repo-paineis []))
                           :get "/meu/sessao-atual" :headers (com-bearer (token (random-uuid) (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)) "sem nenhuma sessao no tenant -> 200, NUNCA 404 (ausencia e' um estado)")
    (is (nil? (:sessao-id body)))
    (is (nil? (:situacao body)))))

(deftest minha-sessao-ignora-agendada-mesmo-sortida-primeiro-pelo-sli
  ;; review MAJOR (revisao final de branch): `sli-sessoes` agrupa por `encerrada_em IS NULL` (verdadeiro p/
  ;; 'aberta' E 'agendada') e ordena esse grupo por `transicionou_em ASC` (mais antiga primeiro — proposito
  ;; do dashboard da Mesa: sinalizar sessao aberta ha' MAIS TEMPO). Uma sessao 'agendada' criada ANTES da
  ;; 'aberta' (cenario normal: pauta futura + sessao de hoje) sortiria primeiro nessa ordem — tomar
  ;; cegamente a PRIMEIRA entrada devolveria a sessao ERRADA (futura, ainda fechada) pro cockpit do
  ;; celular. O handler tem de FILTRAR p/ estados realmente vivos (aberta/suspensa) antes de escolher.
  (let [r (pt/response-for (service-fn #{"vereador"} (fake-repo-paineis [(sessao-agendada) (sessao-em-curso)]))
                           :get "/meu/sessao-atual" :headers (com-bearer (token (random-uuid) (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= "em_curso" (:situacao body))
        "mesmo com 'agendada' na frente na lista crua do sli-sessoes, /meu/sessao-atual devolve a VIVA")))

(deftest minha-sessao-so-agendada-devolve-vazio
  ;; sem NENHUMA sessao realmente viva (so' agendada futura) -> {:sessao-id nil}, nunca a agendada.
  (let [r (pt/response-for (service-fn #{"vereador"} (fake-repo-paineis [(sessao-agendada)]))
                           :get "/meu/sessao-atual" :headers (com-bearer (token (random-uuid) (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (nil? (:sessao-id body)) "'agendada' nao conta como 'sessao atual' — so' aberta/suspensa contam")))

(deftest minha-sessao-sem-papel-vereador-403
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-paineis [(sessao-em-curso)]))
                           :get "/meu/sessao-atual" :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 403 (:status r)) "ator sem papel 'vereador' -> authz grossa nega -> 403 (gate e' vereador, nao secretario)")))

(deftest minha-sessao-sem-token-401
  (let [r (pt/response-for (service-fn #{"vereador"} (fake-repo-paineis []))
                           :get "/meu/sessao-atual")]
    (is (= 401 (:status r)) "rota herda a cadeia de auth: sem token -> 401 (fail-closed)")))

(deftest sli-sessoes-secretario-continua-funcionando
  ;; a rota original /paineis/sli/sessoes (gate 'secretario') e' aditiva — /meu/sessao-atual nao a substitui
  ;; nem colide (rotas distintas, gates distintos).
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-paineis [(sessao-em-curso)]))
                           :get "/paineis/sli/sessoes" :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 200 (:status r)) "GET /paineis/sli/sessoes continua 200 p/ papel secretario, sem regressao")))
