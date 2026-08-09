(ns oplenario.sessoes.presenca-lote-http-in-test
  "Etapa 2c da CHAMADA (§22.6 eixo C) — a borda HTTP do LOTE. `POST /sessoes/:id/presenca/lote` grava N eventos
  de presenca de UMA VEZ (papel 'secretario'), corpo {registros: [{vereador-id, tipo, modalidade, ocorrido-em},
  ...]}. DB-free: RepoSessoes FAKE + idp-dev real — espelha `presenca-http-in-test`, so' que para o envelope do
  lote. O gate de estado/janela E a atomicidade real (tudo-ou-nada contra o Postgres) sao provados a parte, em
  `presenca-lote-db-test` (integracao, PG real); aqui se prova so' a TRADUCAO da borda: 201/400/404/403/401/409."
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
  "`registrar-presenca-lote!` GRAVA o `m` recebido em `capturado` (p/ provar sessao-id/registros/agora, e que
  CADA linha carrega fonte forcada + created-by + id gerado) e ECOA N recibos no FORMATO DO REPO REAL, na
  ordem de `:registros` — um `{:id :ocorrido-em :registrado-em}` por linha."
  [busca-fn capturado]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ ente-id id] (busca-fn ente-id id))
    (registrar-presenca-lote! [_ ente-id m]
      (reset! capturado (assoc m :ente-id ente-id))
      (mapv (fn [{:keys [id ocorrido-em]}]
              {:id id :ocorrido-em ocorrido-em :registrado-em (Instant/parse "2026-06-30T15:00:00Z")})
            (:registros m)))))

(defn- fake-repo-sessoes-que-recusa
  [busca-fn msg]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ ente-id id] (busca-fn ente-id id))
    (registrar-presenca-lote! [_ _ente-id m]
      (throw (ex-info msg {:tipo :conflito/sessao-nao-aceita-presenca :motivo :estado-nao-aceita-presenca
                           :sessao-id (:sessao-id m) :estado "encerrada"})))))

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
(defn- url [sid] (str "/sessoes/" sid "/presenca/lote"))
(defn- corpo [m] (json/write-value-as-string m))

(def ^:private ocorrido "2026-06-30T14:00:00Z")

(defn- linha [vid] {"vereador-id" (str vid) "tipo" "entrada" "modalidade" "plenario" "ocorrido-em" ocorrido})

;; ---------- POST /sessoes/:id/presenca/lote ----------

(deftest lote-201-com-tres-linhas
  (let [ente (random-uuid) sid (random-uuid)
        v1 (random-uuid) v2 (random-uuid) v3 (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) cap)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url sid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"registros" [(linha v1) (linha v2) (linha v3)]}))
        body (ler-json r)]
    (is (= 201 (:status r)) "3 linhas validas -> 201")
    (is (= 3 (count (:recibos body))) "um recibo por linha do lote")
    (is (= sid (:sessao-id @cap)) "o Repo recebeu a sessao-id do path")
    (is (= 3 (count (:registros @cap))) "o Repo recebeu as 3 linhas")
    (is (= [v1 v2 v3] (mapv :vereador-id (:registros @cap)))
        "os vereador-id chegam ao Repo na MESMA ordem do corpo")
    (is (every? #(= "manual_secretaria" (:fonte %)) (:registros @cap))
        "fonte FORCADA em CADA linha (registro humano autenticado)")
    (is (every? #(some? (:id %)) (:registros @cap)) "id gerado server-side em CADA linha")
    (is (every? #(some? (:created-by %)) (:registros @cap)) "created-by injetado (do ator) em CADA linha")
    (is (apply distinct? (map :id (:registros @cap))) "os N ids gerados sao distintos entre si")
    (is (some? (:agora @cap)) "o Repo recebeu `agora` — o teto do clamp aplicado a CADA linha")))

(deftest lote-com-fonte-do-cliente-ignorada-201
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) cap)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url sid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"registros" [(assoc (linha vid) "fonte" "painel_eletronico")]}))]
    (is (= 201 (:status r)))
    (is (= "manual_secretaria" (:fonte (first (:registros @cap))))
        "fonte espuria do cliente e' filtrada pelo allowlist, igual ao POST unitario")))

(deftest lote-vazio-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"registros" []}))]
    (is (= 400 (:status r)) "lote sem nenhuma linha -> 400 (min 1)")))

(deftest lote-acima-do-teto-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        registros (mapv (fn [_] (linha (random-uuid))) (range 201))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"registros" registros}))]
    (is (= 400 (:status r)) "201 linhas excede o teto de 200 -> 400 fail-closed")))

(deftest lote-no-teto-exato-nao-e-recusado-pelo-envelope
  ;; prova que o teto e' 200 (nao 199): exatamente 200 linhas passa pela VALIDACAO do envelope (o fake devolve
  ;; 201; nao ha' gate de estado/janela real aqui, so' a contagem).
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        registros (mapv (fn [_] (linha (random-uuid))) (range 200))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"registros" registros}))]
    (is (= 201 (:status r)) "exatamente 200 linhas -> aceito pelo envelope")))

(deftest lote-vereador-repetido-400
  (let [ente (random-uuid) vid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"registros" [(linha vid) (linha (random-uuid)) (linha vid)]}))]
    (is (= 400 (:status r)) "o MESMO vereador-id duas vezes no lote -> 400, ambiguidade recusada na borda")))

(deftest lote-com-uma-linha-de-tipo-invalido-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"registros" [(linha (random-uuid))
                                                       (assoc (linha (random-uuid)) "tipo" "voando")
                                                       (linha (random-uuid))]}))]
    (is (= 400 (:status r))
        "UMA linha com tipo fora do enum reprova o corpo INTEIRO (Malli valida o vetor todo antes do Repo)")))

(deftest lote-registros-nao-e-lista-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"registros" "nao-e-lista"}))]
    (is (= 400 (:status r)))))

(deftest lote-sessao-que-nao-aceita-registro-409
  (let [ente (random-uuid)
        msg "a sessao esta 'encerrada' e nao aceita mais registro de presenca. Corrija pela ata."
        repo-s (fake-repo-sessoes-que-recusa (fn [_ id] (sessao-canonica ente id)) msg)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"registros" [(linha (random-uuid))]}))]
    (is (= 409 (:status r)) "recusa do gate -> 409, aplicada ao lote inteiro")
    (is (= msg (:erro (ler-json r))) "a mensagem ACIONAVEL do dominio chega ao cliente")))

(deftest lote-sessao-inexistente-404
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"registros" [(linha (random-uuid))]}))]
    (is (= 404 (:status r)))))

(deftest lote-casa-alheia-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica (random-uuid) id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"registros" [(linha (random-uuid))]}))]
    (is (= 403 (:status r)))))

(deftest lote-sem-papel-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"vereador"} repo-s)
                           :post (url (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"registros" [(linha (random-uuid))]}))]
    (is (= 403 (:status r)) "ator sem papel 'secretario' -> 403 (mesma authz grossa do POST unitario)")))

(deftest lote-sem-token-401
  (let [repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid))
                           :headers {"Content-Type" "application/json"}
                           :body (corpo {"registros" [(linha (random-uuid))]}))]
    (is (= 401 (:status r)))))

(deftest lote-id-de-sessao-malformado-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post "/sessoes/nao-e-uuid/presenca/lote"
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"registros" [(linha (random-uuid))]}))]
    (is (= 400 (:status r)))))
