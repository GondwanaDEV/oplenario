(ns oplenario.paineis.minhas-notificacoes-http-in-test
  "Onda E fatia 1 (borda HTTP da inbox) — prova a silhueta end-to-end (controller -> repo -> adapters/out
  -> wire/out), o gate `auth` SEM PAPEL (notificacao e' endereçada a uma IDENTIDADE, nao a um cargo) e o
  escopo por ator. DB-free: RepoPaineis FAKE (reify) + idp-dev real (precedente pendencias-http-in-test)."
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

(defn- fake-repo-paineis
  "Guarda o (ente-id, destinatario) recebido em `visto` e devolve `resultado` — assim o teste prova que a
  borda passa a IDENTIDADE DO ATOR, nunca um valor do request."
  [visto resultado]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-paineis/RepoPaineis
    (minhas-notificacoes [_ ente-id dest] (reset! visto [ente-id dest]) resultado)))

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

(defn- notificacao-canonica [ente dest]
  {:id (random-uuid) :ente-id ente :destinatario-identidade-id dest :categoria "norma_publicada"
   :assunto "A sua proposicao virou lei — Lei 3/2026" :corpo "Ementa: ..."
   :objeto-tipo "proposicao" :objeto-id (random-uuid)
   :criado-em (Instant/parse "2026-07-19T12:00:00Z") :lida-em nil})

(deftest minhas-notificacoes-200
  (let [ente (random-uuid) eu (random-uuid) visto (atom nil)
        repo (fake-repo-paineis visto {:notificacoes [(notificacao-canonica ente eu)] :nao-lidas 1})
        r (pt/response-for (service-fn #{"vereador"} repo)
                           :get "/meu/notificacoes" :headers (com-bearer (token ente eu)))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= [ente eu] @visto) "a identidade vem do ATOR, nunca do request")
    (is (= 1 (:nao-lidas body)))
    (let [n (first (:notificacoes body))]
      (is (= "norma_publicada" (:categoria n)))
      (is (string? (:id n)) "uuid projetado como string")
      (is (= "2026-07-19T12:00:00Z" (:criado-em n)) "instante como string ISO")
      (is (nil? (:lida-em n)) "nao lida -> null")
      (is (not (contains? n :ente-id)) "tenant nao vaza")
      (is (not (contains? n :destinatario-identidade-id)) "o destinatario nao volta no wire (e' sempre 'eu')"))))

(deftest minhas-notificacoes-vazio-200
  (let [r (pt/response-for (service-fn #{"vereador"} (fake-repo-paineis (atom nil) {:notificacoes [] :nao-lidas 0}))
                           :get "/meu/notificacoes" :headers (com-bearer (token (random-uuid) (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)) "criterio 3: ator sem notificacao -> 200")
    (is (= [] (:notificacoes body)))
    (is (= 0 (:nao-lidas body)))))

(deftest minhas-notificacoes-sem-papel-tambem-200
  ;; gate AUTH APENAS (spec §4.5): a notificacao e' endereçada a uma identidade, nao a um cargo — um
  ;; servidor sem papel de vereador tem inbox propria e deve conseguir le-la.
  (let [r (pt/response-for (service-fn #{} (fake-repo-paineis (atom nil) {:notificacoes [] :nao-lidas 0}))
                           :get "/meu/notificacoes" :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 200 (:status r)) "sem papel nenhum -> ainda 200 (o escopo e' de POSSE, nao de cargo)")))

(deftest minhas-notificacoes-sem-token-401
  (let [r (pt/response-for (service-fn #{"vereador"} (fake-repo-paineis (atom nil) {:notificacoes [] :nao-lidas 0}))
                           :get "/meu/notificacoes")]
    (is (= 401 (:status r)) "fail-closed: sem credencial -> 401")))

;; ---------- Task 7: POST /meu/notificacoes/:id/lida ----------

(defn- fake-repo-marcacao
  "RepoPaineis fake para POST: guarda o `m` recebido e devolve `resultado` (ou nil = nao e' sua/inexistente)."
  [visto resultado]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-paineis/RepoPaineis
    (marcar-notificacao-lida! [_ ente-id m] (reset! visto [ente-id m]) resultado)))

(deftest marcar-lida-200
  (let [ente (random-uuid) eu (random-uuid) id (random-uuid) visto (atom nil)
        r (pt/response-for (service-fn #{"vereador"} (fake-repo-marcacao visto {:id id :lida-em (Instant/parse "2026-07-19T13:00:00Z")}))
                           :post (str "/meu/notificacoes/" id "/lida")
                           :headers (com-bearer (token ente eu)))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= ente (first @visto)))
    (is (= eu (:destinatario-identidade-id (second @visto)))
        "o destinatario e' o do ATOR, nunca do path")
    (is (= (str id) (:id body)))
    (is (= "2026-07-19T13:00:00Z" (:lida-em body)))))

(deftest marcar-lida-de-outro-ator-404
  (let [r (pt/response-for (service-fn #{"vereador"} (fake-repo-marcacao (atom nil) nil))
                           :post (str "/meu/notificacoes/" (random-uuid) "/lida")
                           :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 404 (:status r)) "criterio 4: id de outro destinatario -> 404, nunca 200 silencioso")))

(deftest marcar-lida-id-malformado-404
  (let [r (pt/response-for (service-fn #{"vereador"} (fake-repo-marcacao (atom nil) nil))
                           :post "/meu/notificacoes/nao-e-uuid/lida"
                           :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 404 (:status r)) "id que nao parseia = recurso inexistente (nao vaza nada, nunca 500)")))

(deftest marcar-lida-sem-token-401
  (let [r (pt/response-for (service-fn #{"vereador"} (fake-repo-marcacao (atom nil) nil))
                           :post (str "/meu/notificacoes/" (random-uuid) "/lida"))]
    (is (= 401 (:status r)))))
