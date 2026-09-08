(ns oplenario.sessoes.sessoes-listagem-http-in-test
  "GET /sessoes — a LISTAGEM GERAL das sessoes do ente (ledger de prontidao #16, MATA): a home do vereador
  so tinha POST /sessoes (agendar) e GET /sessoes/:id (uma so'), entao o frontend sempre chamava com
  `sessoes=[]` e 'nao sei' virava 'nao ha' — a mesma sessao que /paineis/mesa mostrava correta.

  AUTHZ: uma LISTAGEM autoriza POR LINHA — `logic/pode-ver-quorum-da-sessao?` roda sobre CADA sessao do
  resultado (a MESMA politica de `/quorum`/`/composicao`/`/tribuna`), nunca um `check!` unico na entrada.
  Os testes B1b abaixo cravam essa fronteira: uma sessao secreta some da LISTA para quem nao e' secretario
  (nao 403 na rota inteira — 403 seria a rota errada de responder e ainda vazaria a EXISTENCIA da secreta
  pelo status).

  PAYLOAD: cada item e' o MESMO shape de `SessaoOut` (a resposta de `GET /sessoes/:id`) — vocabulario
  unico, nunca dois formatos para a mesma sessao. Este teste cobre so' o backend; o frontend NAO e' escopo
  desta fatia."
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

(def ^:private t0 (Instant/parse "2026-06-30T13:00:00Z"))
(defn- mais [^Instant t s] (.plusSeconds t s))

;; `transmite-publica`/`estado` NAO sao decoracao — sao as colunas que `logic/pode-ver-quorum-da-sessao?`
;; e a ordenacao leem (mesmo racional de `tribuna_http_in_test`: uma fixture que omitisse deixaria o gate
;; ou a ordem verdes por acidente).
(defn- sessao [ente-id id estado tipo transmite & {:keys [agendada-para aberta-em encerrada-em]}]
  {:id id :ente-id ente-id :estado estado :tipo-sessao tipo :transmite-publica transmite
   :sessao-legislativa-id (random-uuid) :numero-sequencial 1 :modalidade "presencial"
   :delibera true :gera-ata-regimental true :permite-voto-secreto false :permite-modalidade-remota true
   :agendada-para agendada-para :aberta-em aberta-em :encerrada-em encerrada-em :motivo-nao-realizada nil
   :lock-version 0})

(defn- fake-repo-sessoes [listar-fn]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (listar-sessoes [_ ente-id] (listar-fn ente-id))))

(defn- fake-repo-cadastros-roster []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cadastros-comp/RepoCadastros
    (roster-da-casa [_ _ente-id _data] [])))

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
                                   :repo-cadastros (fake-repo-cadastros-roster)
                                   :objeto-store nil})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id]
  (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))

(defn- com-auth [tok] {"authorization" (str "Bearer " tok)})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

;; ---------- 1: lista vazia -> 200 com {:sessoes []}, nunca 404 ----------

(deftest sem-sessao-nenhuma-200-com-lista-vazia
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_] []))
        r (pt/response-for (service-fn* #{} repo-s)
                           :get "/sessoes" :headers (com-auth (token ente (random-uuid))))]
    (is (= 200 (:status r)) "lista vazia NAO e' 404 -- a Casa existe, so' nao tem sessao nenhuma")
    (is (= [] (:sessoes (ler-json r))))))

;; ---------- 2: a fronteira da sessao secreta -- FILTRO, nunca 403 na rota inteira ----------

(deftest sessao-secreta-some-da-lista-para-quem-nao-e-secretario
  (let [ente (random-uuid) publica (random-uuid) secreta (random-uuid)
        repo-s (fake-repo-sessoes
                (fn [eid] [(sessao eid publica "aberta" "ordinaria" true)
                          (sessao eid secreta "aberta" "secreta" false)]))
        r (pt/response-for (service-fn* #{} repo-s)
                           :get "/sessoes" :headers (com-auth (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)) "a existencia de UMA secreta nunca barra a rota inteira -- so' filtra a linha")
    (is (= [(str publica)] (mapv :id (:sessoes body)))
        "so' a publica aparece -- a secreta e' invisivel: nem id, nem tipo, nem estado, nada")
    (is (not (re-find #"(?i)secreta" (:body r))) "nenhum vestigio da secreta atravessa o payload")))

;; A4 (revisao adversarial de conserta-3-mata): nos testes acima o fake SEMPRE devolve linhas com o
;; `ente-id` do PROPRIO ator (`(sessao eid ...)`, onde `eid` e' o `ente-id` que o controller passou pro
;; repo) -- dropar o check de tenant em `logic/pode-ver-sessao?` passaria em 100% deles. `pode-ver-sessao?`
;; e' documentada como DEFESA-EM-PROFUNDIDADE (`logic.clj:82-87`: "a RLS ja escopa a query; isto barra um
;; recurso de outro ente que escape por bug de query/repo"). Este teste simula EXATAMENTE esse bug: um
;; repo (por hipotese, com WHERE ente_id quebrado) devolvendo uma sessao de OUTRO ente -- e prova que o
;; `filter` no controller ainda a barra, mesmo sem depender da RLS. REPROVA se
;; `logic/pode-ver-quorum-da-sessao?`/`pode-ver-sessao?` perder o check de tenant (ex.: virar so'
;; `(or transmite-publica (contains? papeis "secretario"))`).
(deftest sessao-de-outro-ente-nunca-aparece-mesmo-se-o-repo-devolver-por-bug
  (let [ente (random-uuid) outro-ente (random-uuid)
        minha (random-uuid) alheia (random-uuid)
        repo-s (fake-repo-sessoes
                (fn [_eid-pedido]
                  ;; ignora deliberadamente o ente-id pedido -- simula RLS/WHERE quebrado no repo real
                  [(sessao ente minha "aberta" "ordinaria" true)
                   (sessao outro-ente alheia "aberta" "ordinaria" true)]))
        r (pt/response-for (service-fn* #{} repo-s)
                           :get "/sessoes" :headers (com-auth (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= [(str minha)] (mapv :id (:sessoes body)))
        "so' a sessao do PROPRIO ente aparece -- a de outro ente e' barrada pelo filtro de tenant, nao so' pela RLS")))

(deftest sessao-secreta-continua-visivel-ao-secretario
  (let [ente (random-uuid) secreta (random-uuid)
        repo-s (fake-repo-sessoes (fn [eid] [(sessao eid secreta "aberta" "secreta" false)]))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :get "/sessoes" :headers (com-auth (token ente (random-uuid))))]
    (is (= [(str secreta)] (mapv :id (:sessoes (ler-json r))))
        "o secretario ja alcancava esse estado por /chamada -- nao apertar aqui")))

;; ---------- 3: ordenacao -- aberta primeiro, agendadas por data crescente, fechadas por data decrescente ----------

(deftest ordenacao-aberta-depois-agendadas-crescente-depois-fechadas-decrescente
  (let [ente (random-uuid)
        aberta (random-uuid)
        ag-longe (random-uuid) ag-perto (random-uuid)
        fech-antiga (random-uuid) fech-recente (random-uuid)
        repo-s (fake-repo-sessoes
                (fn [eid]
                  ;; embaralhado de proposito -- a ordem da resposta tem de vir da FUNCAO, nao da ordem
                  ;; em que o repo (fake ou real) devolveu as linhas.
                  [(sessao eid fech-antiga "encerrada" "ordinaria" true :encerrada-em t0)
                   (sessao eid ag-longe "agendada" "ordinaria" true :agendada-para (mais t0 (* 3 86400)))
                   (sessao eid fech-recente "encerrada" "ordinaria" true :encerrada-em (mais t0 3600))
                   (sessao eid aberta "aberta" "ordinaria" true :aberta-em t0)
                   (sessao eid ag-perto "agendada" "ordinaria" true :agendada-para (mais t0 86400))]))
        r (pt/response-for (service-fn* #{} repo-s)
                           :get "/sessoes" :headers (com-auth (token ente (random-uuid))))
        ids (mapv :id (:sessoes (ler-json r)))]
    (is (= [(str aberta) (str ag-perto) (str ag-longe) (str fech-recente) (str fech-antiga)] ids)
        "aberta > agendadas (data crescente) > fechadas (data decrescente)")))

(deftest suspensa-conta-como-agora-junto-com-aberta
  ;; decisao explicita: 'suspensa' (recesso de plenario) NAO fecha a sessao -- o vereador que abre a home
  ;; durante um recesso ainda tem "uma sessao agora", entao suspensa entra no MESMO grupo de aberta.
  (let [ente (random-uuid)
        agendada (random-uuid) suspensa (random-uuid)
        repo-s (fake-repo-sessoes
                (fn [eid] [(sessao eid agendada "agendada" "ordinaria" true :agendada-para (mais t0 86400))
                          (sessao eid suspensa "suspensa" "ordinaria" true :aberta-em t0)]))
        r (pt/response-for (service-fn* #{} repo-s)
                           :get "/sessoes" :headers (com-auth (token ente (random-uuid))))
        ids (mapv :id (:sessoes (ler-json r)))]
    (is (= [(str suspensa) (str agendada)] ids) "suspensa vem antes da agendada -- e' 'agora', nao 'futuro'")))

;; ---------- 4: contrato ----------

(deftest contrato-de-topo-fechado-e-item-e-o-mesmo-shape-de-buscar-sessao
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [eid] [(sessao eid sid "aberta" "ordinaria" true :aberta-em t0)]))
        r (pt/response-for (service-fn* #{} repo-s)
                           :get "/sessoes" :headers (com-auth (token ente (random-uuid))))
        body (ler-json r)]
    (is (= #{:sessoes} (set (keys body))) "o contrato de topo e' fechado -- so' :sessoes")
    (is (= #{:id :sessao-legislativa-id :tipo-sessao :numero-sequencial :estado :modalidade :delibera
             :transmite-publica :gera-ata-regimental :permite-voto-secreto :permite-modalidade-remota
             :agendada-para :aberta-em :encerrada-em :motivo-nao-realizada :lock-version}
           (set (keys (first (:sessoes body)))))
        "cada item e' o MESMO shape de SessaoOut (GET /sessoes/:id) -- vocabulario unico, agora incluindo lock-version (ledger de prontidao Fase 8 achado #2)")))

;; ---------- borda: sem token ----------

(deftest sem-token-401
  (let [repo-s (fake-repo-sessoes (fn [_] []))
        r (pt/response-for (service-fn* #{} repo-s) :get "/sessoes")]
    (is (= 401 (:status r)) "rota herda a cadeia de auth: sem token -> 401 (fail-closed)")))
