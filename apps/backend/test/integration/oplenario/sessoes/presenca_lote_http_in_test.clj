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
            [oplenario.cadastros.components.repositorio :as repo-cadastros-comp]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.rotas :as rotas]
            [oplenario.sessoes.components.repositorio :as repo-sessoes])
  (:import (java.time Instant)))

(defn- sessao-canonica
  "`agendada-para` e' obrigatoria desde a revisao da Etapa 2: o gate de ASSENTO resolve o roster na DATA DE
  REFERENCIA da sessao, e uma sessao sem marco nenhum cai em `:conflito/sessao-sem-data` (outro caso)."
  [ente-id id]
  {:id id :ente-id ente-id :estado "aberta" :tipo-sessao "ordinaria"
   :agendada-para (Instant/parse "2026-06-30T13:00:00Z")})

(defn- fake-repo-cadastros
  "O seam `roster-da-casa` do host. `roster` = os vereador-ids COM cadeira; quem nao esta nela toma 409
  `:sem-assento`. Default VAZIO de proposito — um default permissivo manteria verde a rota que aceitava
  qualquer UUID como presente (o achado que esta revisao fechou)."
  [roster]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cadastros-comp/RepoCadastros
    (roster-da-casa [_ _ente-id _data] (mapv (fn [v] {:vereador-id v}) roster))))

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

(defn- service-fn* [papeis repo-s & {:keys [roster] :or {roster []}}]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-sessoes repo-s
                                   :repo-cadastros (fake-repo-cadastros roster)
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
        r (pt/response-for (service-fn* #{"secretario"} repo-s :roster [v1 v2 v3])
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
        r (pt/response-for (service-fn* #{"secretario"} repo-s :roster [vid])
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
        vids (vec (repeatedly 200 random-uuid))
        registros (mapv linha vids)
        r (pt/response-for (service-fn* #{"secretario"} repo-s :roster vids)
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
  (let [ente (random-uuid) vid (random-uuid)
        msg "a sessao esta 'encerrada' e nao aceita mais registro de presenca. Corrija pela ata."
        repo-s (fake-repo-sessoes-que-recusa (fn [_ id] (sessao-canonica ente id)) msg)
        ;; COM assento: o que se prova aqui e' a traducao da recusa do gate de ESTADO (a de assento e' outra).
        r (pt/response-for (service-fn* #{"secretario"} repo-s :roster [vid])
                           :post (url (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"registros" [(linha vid)]}))]
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

;; ---------- REGRESSAO da revisao adversarial ----------

(deftest lote-com-uuid-fora-do-roster-409-e-nenhuma-linha-grava
  ;; MAJOR (security): `vereador_id` nao tem FK (forward-ref, §22.10) e nenhum caminho o validava. Um
  ;; `secretario` legitimo mandava UM request de lote com UUIDs aleatorios e `relacoes/presenca/
  ;; presentes-plenario` — a relacao que o MOTOR de votacao resolve por nome — os contava como presentes,
  ;; porque e' um COUNT sobre os ultimos eventos SEM join a mandato. O quorum fechava, a materia virava norma,
  ;; e as linhas fantasma atravessavam para `transparencia.presenca_parlamentar` (read-model PUBLICO,
  ;; append-only, sem GRANT de DELETE).
  (let [ente (random-uuid) v-real (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) cap)
        r (pt/response-for (service-fn* #{"secretario"} repo-s :roster [v-real])
                           :post (url (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"registros" [(linha v-real)
                                                      (linha (random-uuid))
                                                      (linha (random-uuid))]}))
        body (ler-json r)]
    (is (= 409 (:status r)) "uuid sem assento na data da sessao -> 409, nao 201")
    (is (nil? @cap) "NENHUMA linha chegou ao Repo — o lote e' tudo-ou-nada tambem neste gate")
    (is (= 1 (:indice body)) "o 409 diz QUAL linha reprovou, por posicao no lote")
    (is (some? (:vereador-id body)) "e qual vereador-id — sem isso a tela nao consegue destacar a linha")))

(deftest lote-409-do-gate-de-janela-carrega-a-linha-culpada
  ;; MEDIO: o Repo ja punha `:vereador-id` na ex-data e a BORDA o descartava. Num lote de 21 nomes o corpo
  ;; do 409 nao dizia qual dos 21, e o secretario fazia busca binaria reenviando sublotes, ao vivo.
  (let [ente (random-uuid) v1 (random-uuid) v2 (random-uuid)
        repo-s #_{:clj-kondo/ignore [:missing-protocol-method]}
               (reify repo-sessoes/RepoSessoes
                 (buscar-sessao [_ e id] (sessao-canonica (if (= e ente) ente e) id))
                 (registrar-presenca-lote! [_ _e m]
                   (throw (ex-info "instante fora da janela"
                                   {:tipo :conflito/sessao-nao-aceita-presenca
                                    :motivo :instante-fora-do-dia-da-sessao
                                    :sessao-id (:sessao-id m)
                                    :vereador-id (:vereador-id (second (:registros m)))
                                    :indice 1}))))
        r (pt/response-for (service-fn* #{"secretario"} repo-s :roster [v1 v2])
                           :post (url (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"registros" [(linha v1) (linha v2)]}))
        body (ler-json r)]
    (is (= 409 (:status r)))
    (is (= (str v2) (:vereador-id body)) "o vereador-id da ex-data chega ao cliente")
    (is (= 1 (:indice body)) "e o indice da linha no lote tambem")))

(deftest lote-em-sessao-sem-data-409-acionavel
  ;; efeito colateral consciente do gate de assento: sem `agendada_para` nem `aberta_em` nao ha' DATA para
  ;; resolver a composicao da Casa. Recusa-se com a mesma mensagem acionavel que o GET da chamada ja dava,
  ;; em vez de 500 opaco.
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] {:id id :ente-id ente :estado "aberta" :tipo-sessao "ordinaria"})
                                  (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"registros" [(linha (random-uuid))]}))]
    (is (= 409 (:status r)))
    (is (re-find #"sem data marcada" (:erro (ler-json r))))))
