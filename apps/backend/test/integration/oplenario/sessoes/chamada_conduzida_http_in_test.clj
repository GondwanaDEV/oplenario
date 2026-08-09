(ns oplenario.sessoes.chamada-conduzida-http-in-test
  "Slice §22.6 eixo C, Etapa 2d — a BORDA HTTP do ATO DE CHAMADA CONDUZIDA. `POST /sessoes/:id/chamada`
  (papel 'secretario', SEM CORPO) registra que a chamada foi conduzida -> 201. `conduzida-por` e' INJETADO do
  ator (nunca do corpo — nao ha' corpo nesta rota); `membros-da-casa` e' computado pelo controller a partir do
  seam `roster-da-casa` (fake aqui) DENTRO da tx do Repo; `ocorrido-em` vem do RELOGIO real do host (esta rota nao aceita relogio
  fixo injetado — os testes so' verificam que ALGUM instante chegou ao Repo, mesmo contrato de
  `presenca-http-in-test/confirmar-presenca-handler`). Carrega a sessao (nil->404), pode-ver-sessao? (mesma
  Casa->403). 409 (`:conflito/chamada`) quando o Repo recusa. DB-free: RepoSessoes FAKE + RepoCadastros fake
  (roster) + idp-dev real — espelha o incidente-http-in-test/justificativa-http-in-test."
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
            [oplenario.sessoes.components.repositorio :as repo-sessoes]
            [oplenario.sessoes.logic :as logic])
  (:import (java.time Instant)))

(defn- sessao-canonica
  "`agendada-para` e' o que resolve a DATA DE COMPOSICAO do roster (controllers/data-de-referencia,
  chamado por `registrar-chamada-conduzida` p/ congelar o denominador) — sem ela a rota cairia em
  :conflito/sessao-sem-data, que e' outro caso (nao testado aqui; mesmo contrato de justificativa-http-in-test)."
  [ente-id id]
  {:id id :ente-id ente-id :estado "aberta" :tipo-sessao "ordinaria"
   :agendada-para (Instant/parse "2026-06-20T13:00:00Z")})

(defn- fake-repo-sessoes
  "RepoSessoes fake (parcial): `busca-fn` resolve a sessao; `registrar-chamada-conduzida!` GRAVA o mapa
  recebido em `capturado` e ECOA o recibo NO FORMATO DO REPO REAL {:id :ocorrido-em :registrado-em} — ecoar
  so' {:id} manteria este ns verde contra um contrato :closed que exige mais (mesma armadilha de fixture que
  redigita o vocabulario alheio, agora no formato de saida)."
  [busca-fn capturado]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ ente-id id] (busca-fn ente-id id))
    (registrar-chamada-conduzida! [_ ente-id m]
      (reset! capturado (assoc m :ente-id ente-id))
      ;; O denominador deixou de ser computado pelo controller e passou a sair de DENTRO da tx do Repo (a
      ;; conta roster-only divergia do que a leitura publica). O fake computa pela MESMA funcao de dominio
      ;; sobre o roster que recebeu — redigitar aqui um numero constante e' a armadilha de fixture que este
      ;; repo ja pagou caro: manteria verde um recibo que o wire :closed exige e que o Repo real derivaria
      ;; de outra maneira.
      {:id (:id m) :ocorrido-em (:ocorrido-em m) :registrado-em (:ocorrido-em m)
       :membros-da-casa (logic/membros-da-casa-da-chamada (:roster m) [] [])})))

(defn- fake-repo-sessoes-que-recusa
  [busca-fn msg]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ ente-id id] (busca-fn ente-id id))
    (registrar-chamada-conduzida! [_ _ente-id m]
      (throw (ex-info msg {:tipo :conflito/chamada :motivo :estado-nao-aceita-presenca
                           :sessao-id (:sessao-id m) :estado "encerrada"})))))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- fake-repo-cadastros
  "O seam `roster-da-casa` que o host injeta — aqui, quantos/quem sao os membros p/ o controller congelar o
  denominador."
  [roster]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cadastros-comp/RepoCadastros
    (roster-da-casa [_ ente-id data] (roster ente-id data))))

(defn- service-fn*
  [papeis repo-s & {:keys [roster] :or {roster (constantly [])}}]
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
(defn- com-json [tok] {"authorization" (str "Bearer " tok)})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))
(defn- url [sid] (str "/sessoes/" sid "/chamada"))

(defn- roster-com [& vereador-ids]
  (fn [_ente _data] (mapv (fn [v] {:vereador-id v}) vereador-ids)))

;; ---------- sucesso ----------

(deftest conduzir-chamada-201
  (let [ente (random-uuid) sid (random-uuid) op (random-uuid)
        v1 (random-uuid) v2 (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) cap)
        r (pt/response-for (service-fn* #{"secretario"} repo-s :roster (roster-com v1 v2))
                           :post (url sid) :headers (com-json (token ente op)))
        body (ler-json r)]
    (is (= 201 (:status r)) "papel + mesma Casa -> 201")
    (is (= (str (:id @cap)) (:id body)) "recibo carrega o id do ato gravado")
    (is (= sid (:sessao-id @cap)) "Repo recebeu a sessao-id (path)")
    (is (= op (:conduzida-por @cap)) "conduzida-por INJETADO do ator (nunca ha' corpo nesta rota)")
    (is (= 2 (count (:roster @cap))) "o ROSTER (2 vereadores) chega ao Repo — quem congela o denominador e' a tx")
    (is (some? (:ocorrido-em @cap)) "ocorrido-em veio do relogio do servidor")
    (is (= (:conduzida-por @cap) op) "conduzida-por no wire e' o mesmo do ator")
    (is (= 2 (:membros-da-casa body)) "recibo ja expoe o denominador congelado")
    (is (some? (:id @cap)) "id gerado server-side")))

(deftest conduzir-chamada-roster-vazio-chega-vazio-ao-repo
  ;; A RECUSA do denominador zero mora na tx do Repo (onde o numero e' computado), nao aqui: este ns e'
  ;; DB-free e o fake nao a implementa. O que se prova aqui e' que o roster VAZIO chega intacto — a recusa
  ;; em si esta em `chamada-conduzida-gate-estado-test/conduzir-com-casa-sem-membros-*`.
  (let [ente (random-uuid) op (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) cap)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid)) :headers (com-json (token ente op)))]
    (is (= 201 (:status r)))
    (is (empty? (:roster @cap)) "roster vazio chega vazio — o controller nao inventa composicao")))

(deftest conduzir-chamada-reenvio-deduplicado-responde-200
  ;; MENOR: a rota nao tem corpo, entao o cliente nao tem como sinalizar "e' o mesmo ato". Um duplo clique
  ;; gravava dois atos em `chamada_conduzida` (append-only, sem DELETE), e a folha da sessao passava a
  ;; registrar uma reverificacao de quorum que nao aconteceu. O Repo marca o reenvio; a borda o distingue.
  (let [ente (random-uuid) op (random-uuid)
        repo-s #_{:clj-kondo/ignore [:missing-protocol-method]}
               (reify repo-sessoes/RepoSessoes
                 (buscar-sessao [_ _e id] (sessao-canonica ente id))
                 (registrar-chamada-conduzida! [_ _e m]
                   {:id (:id m) :ocorrido-em (:ocorrido-em m) :registrado-em (:ocorrido-em m)
                    :membros-da-casa 3 :ja-registrado true}))
        r (pt/response-for (service-fn* #{"secretario"} repo-s :roster (roster-com (random-uuid)))
                           :post (url (random-uuid)) :headers (com-json (token ente op)))]
    (is (= 200 (:status r)) "reenvio deduplicado -> 200 (o ato ja existia), nunca um segundo 201")
    (is (= 3 (:membros-da-casa (ler-json r))) "o corpo e' o ato EXISTENTE, no mesmo shape")))

(deftest conduzir-chamada-em-sessao-sem-data-409
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] {:id id :ente-id ente :estado "aberta" :tipo-sessao "ordinaria"})
                                  (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid)) :headers (com-json (token ente (random-uuid))))]
    (is (= 409 (:status r)) "sem data nao ha' composicao a congelar — 409 acionavel, nao 500")
    (is (re-find #"sem data marcada" (:erro (ler-json r))))))

;; ---------- authz / not-found ----------

(deftest conduzir-chamada-sessao-inexistente-404
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid)) :headers (com-json (token ente (random-uuid))))]
    (is (= 404 (:status r)) "sessao inexistente -> 404")))

(deftest conduzir-chamada-casa-alheia-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica (random-uuid) id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid)) :headers (com-json (token ente (random-uuid))))]
    (is (= 403 (:status r)) "sessao de ente alheio -> 403")))

(deftest conduzir-chamada-sem-papel-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"vereador"} repo-s)
                           :post (url (random-uuid)) :headers (com-json (token ente (random-uuid))))]
    (is (= 403 (:status r)) "sem papel 'secretario' -> 403")))

(deftest conduzir-chamada-sem-token-401
  (let [repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid)) :headers {})]
    (is (= 401 (:status r)) "sem token -> 401")))

;; ---------- 409 (traducao do gate; o gate DE VERDADE contra o Postgres e' provado no gate-estado-test) ----------

(deftest conduzir-chamada-recusada-pelo-gate-409
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes-que-recusa (fn [_ id] (sessao-canonica ente id)) "a sessao esta 'encerrada'")
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid)) :headers (com-json (token ente (random-uuid))))]
    (is (= 409 (:status r)) "gate recusa -> 409, nao 500")
    (is (re-find #"encerrada" (:erro (ler-json r))) "mensagem do dominio repassada ao cliente")))
