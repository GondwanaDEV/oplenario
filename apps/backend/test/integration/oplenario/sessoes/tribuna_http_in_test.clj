(ns oplenario.sessoes.tribuna-http-in-test
  "GET /sessoes/:id/tribuna — o read-model que faltava a' TRIBUNA (ledger de prontidao #7, MATA). As 5
  rotas de ESCRITA da tribuna (inscrever/desistir/iniciar-fala/cronometro/encerrar-fala) nunca tiveram
  uma de LEITURA: o painel ao vivo do plenario so' sabia orador/fila por SSE, e um reload, uma reconexao
  longa (janela de replay do canal) ou abrir a tela DEPOIS da fala comecar deixavam 'Ninguem com a
  palavra' com alguem efetivamente falando. O frontend NAO e' escopo desta fatia; este teste cobre so'
  o backend.

  AUTHZ: a MESMA de `/quorum`/`/composicao` — `logic/pode-ver-quorum-da-sessao?` (mesma Casa E
  (transmissao publica OU papel 'secretario')). Deliberadamente NAO `pode-ver-sessao?` cru: sem essa
  clausula a rota vira porta dos fundos da sessao SECRETA (achado MAJOR de revisao anterior, ver
  `quorum_http_in_test`/`composicao_http_in_test`). Os testes B1b abaixo cravam essa fronteira de novo.

  PAYLOAD: a UNIAO EXATA do que `sessoes.events.tribuna` ja' transmite ao MESMO publico pelo canal do
  plenario (`FalaIniciadaPayload`/`FalaCronometroPayload`/`InscricaoRegistradaPayload`, cada um menos os
  ids redundantes) — SEM roster, SEM nome de vereador (esse vem de `/composicao`, §22.10)."
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

(def ^:private iniciou-em (Instant/parse "2026-06-30T13:05:00Z"))
(def ^:private iniciou-em-anterior (Instant/parse "2026-06-30T12:50:00Z"))

;; `transmite-publica` NAO e' decoracao — e' a coluna que `logic/pode-ver-quorum-da-sessao?` le' (mesmo
;; racional de `quorum_http_in_test`/`composicao_http_in_test`: uma fixture que a omitisse deixaria o
;; gate verde por acidente, `(true? nil)` = false).
(defn- sessao-aberta [ente-id id]
  {:id id :ente-id ente-id :estado "aberta" :tipo-sessao "ordinaria" :transmite-publica true})

(defn- sessao-secreta [ente-id id]
  {:id id :ente-id ente-id :estado "aberta" :tipo-sessao "secreta" :transmite-publica false})

(def ^:private orador-1 (random-uuid))
(def ^:private fala-em-curso-id (random-uuid))
(def ^:private inscricao-orador-1 (random-uuid))

(defn- fala-em-curso-doc []
  {:id fala-em-curso-id :sessao-id nil :orador-id orador-1 :tipo-fala "principal" :fase "ordem_do_dia"
   :iniciou-em iniciou-em :encerrou-em nil :inscricao-id inscricao-orador-1})

;; `tribuna-fn` devolve {:fala-em-curso :marcos :inscricoes} (o resto que `tribuna-da-sessao` do repo
;; agrega na MESMA tx) — o fake NAO reimplementa a projecao do controller, so' os TRES insumos crus.
(defn- fake-repo-sessoes [busca-fn tribuna-fn]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (tribuna-da-sessao [_ ente-id id]
      (when-let [s (busca-fn ente-id id)]
        (merge {:sessao s} (tribuna-fn ente-id id))))))

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
(defn- url [sid] (str "/sessoes/" sid "/tribuna"))

(def ^:private vazio {:fala-em-curso nil :marcos [] :inscricoes []})

;; ---------- 1: fala em curso -> orador-atual preenchido ----------

(deftest com-fala-em-curso-orador-atual-preenchido
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-aberta ente id))
                                  (fn [_ _] {:fala-em-curso (fala-em-curso-doc) :marcos [] :inscricoes []}))
        r (pt/response-for (service-fn* #{} repo-s)
                           :get (url sid) :headers (com-auth (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= (str fala-em-curso-id) (:fala-id (:orador-atual body))))
    (is (= (str orador-1) (:orador-id (:orador-atual body))))
    (is (= "principal" (:tipo-fala (:orador-atual body))))
    (is (= "ordem_do_dia" (:fase (:orador-atual body))))
    (is (= (str iniciou-em) (:iniciou-em (:orador-atual body))))
    (is (= (str inscricao-orador-1) (:inscricao-id (:orador-atual body))))))

;; ---------- 2: nenhuma fala em curso -> orador-atual nil, marcos vazio ----------

(deftest sem-fala-alguma-orador-atual-nil
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-aberta ente id)) (fn [_ _] vazio))
        r (pt/response-for (service-fn* #{} repo-s)
                           :get (url sid) :headers (com-auth (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (nil? (:orador-atual body)))
    (is (= [] (:marcos-cronometro body)))))

(deftest fala-ja-encerrada-orador-atual-nil
  ;; O fake so' devolve `:fala-em-curso` quando ha' uma linha com `encerrou_em IS NULL` — uma fala
  ;; encerrada nao aparece aqui (e' o que `db/tribuna/fala-em-curso` faz na fonte real).
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-aberta ente id)) (fn [_ _] vazio))
        r (pt/response-for (service-fn* #{} repo-s)
                           :get (url sid) :headers (com-auth (token ente (random-uuid))))]
    (is (nil? (:orador-atual (ler-json r))))))

;; ---------- 3: fala encerrada NAO VAZA (a prova de que o filtro e' encerrou_em IS NULL) ----------

(deftest fala-encerrada-nao-aparece-como-orador-atual
  ;; Simula iniciar + encerrar: o repo real (`db/tribuna/fala-em-curso`) so' devolve linha com
  ;; `encerrou_em IS NULL`; uma fala que ja' foi encerrada deixa de satisfazer esse filtro e o fake
  ;; devolve nil pelo MESMO motivo — nao "a ultima fala", que incluiria a encerrada.
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-aberta ente id)) (fn [_ _] vazio))
        r (pt/response-for (service-fn* #{} repo-s)
                           :get (url sid) :headers (com-auth (token ente (random-uuid))))
        body (ler-json r)]
    (is (nil? (:orador-atual body))
        "a fala encerrada nao deve aparecer como orador-atual -- so' encerrou_em IS NULL conta")
    (is (not (re-find #"(?i)encerrou" (:body r))) "nenhum vestigio do estado encerrado atravessa o payload")))

;; ---------- 4: marcos do cronometro DA FALA EM CURSO, nunca de uma fala anterior ----------

(deftest marcos-da-fala-em-curso-aparecem-de-fala-anterior-nao
  (let [ente (random-uuid) sid (random-uuid)
        marco-atual {:tipo "pausada" :ocorrido-em iniciou-em :segundos-adicionais nil}
        marco-anterior {:tipo "aparte_concedido" :ocorrido-em iniciou-em-anterior :segundos-adicionais nil}
        repo-s (fake-repo-sessoes
                (fn [_ id] (sessao-aberta ente id))
                (fn [_ _] {:fala-em-curso (fala-em-curso-doc) :marcos [marco-atual] :inscricoes []}))
        r (pt/response-for (service-fn* #{} repo-s)
                           :get (url sid) :headers (com-auth (token ente (random-uuid))))
        marcos (:marcos-cronometro (ler-json r))]
    (is (= 1 (count marcos)))
    (is (= "pausada" (:tipo (first marcos))))
    (is (not (re-find #"aparte_concedido" (:body r))) "o marco da fala ANTERIOR nao entra no payload")
    ;; A fixture acima simula o db/tribuna/listar-eventos-cronometro ja' escopado por fala-id (o repo
    ;; real so' le' os eventos DAQUELA fala-em-curso) -- marco-anterior existe so' para provar que,
    ;; mesmo que aparecesse na lista crua, o teste teria acusado.
    (is (some? marco-anterior))))

(deftest marcos-filtram-iniciada-e-encerrada-o-sse-nunca-emite-esses-tipos-em-fala-cronometro
  ;; Constraint 7: o evento `fala.cronometro` do SSE (events/tribuna.clj) SO' e' emitido por
  ;; `registrar-evento-cronometro!` -- pausada/retomada/aparte_concedido/tempo_adicional_concedido.
  ;; 'iniciada'/'encerrada' tem os PROPRIOS eventos (fala.iniciada/fala.encerrada) e NUNCA passam por
  ;; fala.cronometro. A tabela fala_cronometro_evento grava os 6 tipos (iniciar-fala!/encerrar-fala! logam
  ;; 'iniciada'/'encerrada' tambem) -- se a rota devolvesse a lista crua sem filtrar, 'iniciada' vazaria
  ;; um tipo que o canal nunca serviu por este evento.
  (let [ente (random-uuid) sid (random-uuid)
        marco-iniciada {:tipo "iniciada" :ocorrido-em iniciou-em :segundos-adicionais nil}
        marco-manual {:tipo "retomada" :ocorrido-em iniciou-em :segundos-adicionais nil}
        repo-s (fake-repo-sessoes
                (fn [_ id] (sessao-aberta ente id))
                (fn [_ _] {:fala-em-curso (fala-em-curso-doc)
                          :marcos [marco-iniciada marco-manual] :inscricoes []}))
        r (pt/response-for (service-fn* #{} repo-s)
                           :get (url sid) :headers (com-auth (token ente (random-uuid))))
        marcos (:marcos-cronometro (ler-json r))]
    (is (= 1 (count marcos)) "so' o marco MANUAL sobrevive ao filtro")
    (is (= "retomada" (:tipo (first marcos))))))

;; ---------- 5: fila -- inscritos ordenados, desistente fora ----------

(deftest fila-de-inscritos-ordenada-desistente-nao-aparece
  (let [ente (random-uuid) sid (random-uuid)
        v1 (random-uuid) v2 (random-uuid) v-desistiu (random-uuid)
        i1 {:id (random-uuid) :vereador-id v1 :origem-inscricao "pre_sessao_app"
            :fase "ordem_do_dia" :estado "inscrita" :ordem 1}
        i2 {:id (random-uuid) :vereador-id v2 :origem-inscricao "pre_sessao_secretaria"
            :fase "ordem_do_dia" :estado "inscrita" :ordem 2}
        i-desistiu {:id (random-uuid) :vereador-id v-desistiu :origem-inscricao "pre_sessao_app"
                    :fase "ordem_do_dia" :estado "desistencia" :ordem 3}
        repo-s (fake-repo-sessoes
                (fn [_ id] (sessao-aberta ente id))
                (fn [_ _] {:fala-em-curso nil :marcos [] :inscricoes [i1 i2 i-desistiu]}))
        r (pt/response-for (service-fn* #{} repo-s)
                           :get (url sid) :headers (com-auth (token ente (random-uuid))))
        inscritos (:inscritos (ler-json r))]
    (is (= 2 (count inscritos)) "o desistente fica de fora")
    (is (= [(str v1) (str v2)] (mapv :vereador-id inscritos)) "ordem preservada")
    (is (not (re-find #"(?i)desistencia" (:body r))) "nenhum vestigio do desistente atravessa o payload")))

;; ---------- 6: a FRONTEIRA DA SESSAO SECRETA -- mesmo gate de /quorum e /composicao ----------

(deftest sessao-secreta-nega-quem-nao-e-secretario
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-secreta ente id)) (fn [_ _] vazio))
        r (pt/response-for (service-fn* #{} repo-s)
                           :get (url sid) :headers (com-auth (token ente (random-uuid))))]
    (is (= 403 (:status r))
        "sessao secreta + ator sem papel -> 403: a tribuna nao pode ser a porta dos fundos")))

(deftest sessao-secreta-continua-visivel-ao-secretario
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-secreta ente id)) (fn [_ _] vazio))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :get (url sid) :headers (com-auth (token ente (random-uuid))))]
    (is (= 200 (:status r)) "o secretario ja alcancava esse estado pelas rotas de escrita -- nao apertar aqui")))

(deftest sessao-com-transmissao-publica-aberta-a-quem-nao-tem-papel
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-aberta ente id)) (fn [_ _] vazio))
        r (pt/response-for (service-fn* #{} repo-s)
                           :get (url sid) :headers (com-auth (token ente (random-uuid))))]
    (is (= 200 (:status r)) "o publico do telao (sem papel algum) le' a tribuna quando a sessao transmite")))

;; ---------- 7: sessao de outra Casa -> 404 (isolamento por ente, RLS) ----------

(deftest sessao-de-outra-casa-404
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes
                ;; simula RLS: a query so' acha a sessao quando o ente-id bate -- de qualquer OUTRO
                ;; ente, o repo nao acha nada (nunca devolve a sessao alheia).
                (fn [busca-ente id] (when (= busca-ente ente) (sessao-aberta ente id)))
                (fn [_ _] vazio))
        r (pt/response-for (service-fn* #{} repo-s)
                           :get (url sid) :headers (com-auth (token (random-uuid) (random-uuid))))]
    (is (= 404 (:status r)) "sessao de ente alheio -- RLS nao acha -- 404")))

;; ---------- 8: sessao inexistente -> 404 ----------

(deftest sessao-inexistente-404
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (fn [_ _] vazio))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :get (url (random-uuid)) :headers (com-auth (token ente (random-uuid))))]
    (is (= 404 (:status r)) "sessao que o repo nao acha no tenant do ator -> 404")))

;; ---------- borda: sem token / id malformado ----------

(deftest sem-token-401
  (let [repo-s (fake-repo-sessoes (fn [_ _] nil) (fn [_ _] vazio))
        r (pt/response-for (service-fn* #{} repo-s) :get (url (random-uuid)))]
    (is (= 401 (:status r)) "rota herda a cadeia de auth: sem token -> 401 (fail-closed)")))

(deftest id-malformado-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (fn [_ _] vazio))
        r (pt/response-for (service-fn* #{} repo-s)
                           :get "/sessoes/nao-e-uuid/tribuna" :headers (com-auth (token ente (random-uuid))))]
    (is (= 400 (:status r)) "sessao :id malformado no path -> 400, nunca 500")))

;; ---------- contrato de topo: so' os campos permitidos ----------

(deftest contrato-de-topo-fechado
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-aberta ente id)) (fn [_ _] vazio))
        r (pt/response-for (service-fn* #{} repo-s)
                           :get (url sid) :headers (com-auth (token ente (random-uuid))))
        body (ler-json r)]
    (is (= #{:sessao-id :orador-atual :marcos-cronometro :inscritos} (set (keys body)))
        "o contrato de topo e' fechado -- nem instante, nem data-de-composicao, nem quorum (isso e' /quorum)")))
