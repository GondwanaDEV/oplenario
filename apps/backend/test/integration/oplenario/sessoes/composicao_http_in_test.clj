(ns oplenario.sessoes.composicao-http-in-test
  "Tribuna nominal (§22.6 eixo C): `GET /sessoes/:id/composicao` — 'quem sao os parlamentares desta sessao,
  por nome'. Fecha o defeito do painel ao vivo do plenario (`/sessoes/:id/plenario`): o SSE carrega so'
  `orador-id`, e ate' esta fatia nao havia rota que o telao pudesse consultar para resolver o NOME — o
  avatar mostrava 2 caracteres do UUID e a fila de inscritos mostrava o prefixo do UUID onde deveria ir o
  nome. O frontend NAO e' escopo desta fatia; este teste cobre so' o backend.

  TERCEIRO chamador de `chamada-da-sessao*` (os outros dois sao `chamada-da-sessao` nominal e
  `quorum-da-sessao` magra) — nao ha' aqui uma segunda aritmetica de composicao da Casa, mesma disciplina
  provada em `quorum_http_in_test` (de onde os fakes deste arquivo sao copiados).

  AUTHZ: a MESMA de `/quorum` — `logic/pode-ver-quorum-da-sessao?` (mesma Casa E (transmissao publica OU
  papel 'secretario')). Deliberadamente NAO `pode-ver-sessao?` cru: sem essa clausula a rota vira porta dos
  fundos da sessao SECRETA (achado MAJOR de revisao anterior, ver `quorum_http_in_test`). Os testes B1b
  abaixo cravam essa fronteira de novo para a rota nova, porque esta rota carrega NOME — o argumento de
  'nao carrega nada nominal' que justificava o `/quorum` nao vale aqui, e a authz nao pode afrouxar por isso.

  PAYLOAD: so' `{vereador-id, nome-parlamentar, cargo-mesa}` por membro (sem `partido` — verificado que a
  rota publica de vereador nao o serve; sem `nome` civil, sem `estado`, sem `justificativa`/`motivo`, sem
  `desde`/`fonte`/`registrado-em`/`inconsistencia-cadastro` — nenhum desses e' dado de identidade PUBLICA).
  Linhas `sem-assento` (vereador que o roster da data nao situa na Casa) NAO entram em `membros` — elas
  contam no quorum, mas nao sao 'quem compoe a Casa'."
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

(def ^:private aberta-em (Instant/parse "2026-06-30T13:00:00Z"))
(def ^:private agendada-para (Instant/parse "2026-06-30T13:00:00Z"))
(def ^:private encerrada-em (Instant/parse "2026-06-30T18:00:00Z"))

;; `transmite-publica` NAO e' decoracao — e' a coluna que `logic/pode-ver-quorum-da-sessao?` le' (mesmo
;; racional de `quorum_http_in_test`: uma fixture que a omitisse deixaria o gate verde por acidente,
;; `(true? nil)` = false).
(defn- sessao-aberta [ente-id id]
  {:id id :ente-id ente-id :estado "aberta" :tipo-sessao "ordinaria" :transmite-publica true
   :agendada-para agendada-para :aberta-em aberta-em :encerrada-em nil})

(defn- sessao-encerrada [ente-id id]
  {:id id :ente-id ente-id :estado "encerrada" :tipo-sessao "ordinaria" :transmite-publica true
   :agendada-para agendada-para :aberta-em aberta-em :encerrada-em encerrada-em})

(defn- sessao-secreta [ente-id id]
  {:id id :ente-id ente-id :estado "aberta" :tipo-sessao "secreta" :transmite-publica false
   :agendada-para agendada-para :aberta-em aberta-em :encerrada-em nil})

(defn- fake-repo-sessoes [busca-fn chamada-fn]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ ente-id id] (busca-fn ente-id id))
    (chamada-da-sessao [_ ente-id id agora]
      (when-let [s (busca-fn ente-id id)]
        (merge {:sessao s :instante (logic/instante-de-avaliacao s agora)}
               (chamada-fn ente-id id agora))))))

(defn- fake-repo-cadastros-roster [roster-fn]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cadastros-comp/RepoCadastros
    (roster-da-casa [_ ente-id data] (roster-fn ente-id data))))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- service-fn* [papeis repo-s roster-fn]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-sessoes repo-s
                                   :repo-cadastros (fake-repo-cadastros-roster (or roster-fn (fn [_ _] [])))
                                   :objeto-store nil})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id]
  (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))

(defn- com-auth [tok] {"authorization" (str "Bearer " tok)})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))
(defn- url [sid] (str "/sessoes/" sid "/composicao"))

;; Nomes escolhidos p/ afirmar sobre o CONTEUDO do corpo cru: `nome-parlamentar` tem de aparecer,
;; `nome` civil (distinto do parlamentar) NUNCA. Um licenciado (fora do roster nominal? nao — o
;; licenciado SEGUE no roster, so' fora do denominador de quorum: ver `logic/derivar-linha-chamada`) e
;; um orfao SEM ASSENTO (fora do roster da data) sao o par que discrimina o filtro `sem-assento`.
(def ^:private v-ana (random-uuid))
(def ^:private v-bruno (random-uuid))
(def ^:private v-licenciado (random-uuid))
(def ^:private v-orfao (random-uuid))

(defn- roster-rico [_ _]
  [{:vereador-id v-ana :nome "Ana da Silva Nascimento" :nome-parlamentar "Ana Nascimento" :partido "PDT"
    :estado-mandato "vigente" :cargo-mesa "presidente"}
   {:vereador-id v-bruno :nome "Bruno Costa Ferreira" :nome-parlamentar "Bruno Ferreira" :partido "PT"
    :estado-mandato "vigente" :cargo-mesa nil}
   {:vereador-id v-licenciado :nome "Dario Melo Junior" :nome-parlamentar "Dario Melo" :partido "MDB"
    :estado-mandato "licenciado" :cargo-mesa nil}])

(defn- chamada-rica [_ _ _]
  {:presencas [{:vereador-id v-ana :tipo "entrada" :modalidade "plenario"
                :fonte "manual_secretaria" :ocorrido-em aberta-em :registrado-em aberta-em}
               {:vereador-id v-orfao :tipo "entrada" :modalidade "plenario"
                :fonte "manual_secretaria" :ocorrido-em aberta-em :registrado-em aberta-em}]
   ;; `motivo` e' dado de saude de proposito: se vazar para esta rota, B3 acusa.
   :justificativas [{:id (random-uuid) :vereador-id v-bruno :estado "aprovada"
                     :motivo "Internacao hospitalar - cirurgia cardiaca" :decidido-por (random-uuid)
                     :decidido-em encerrada-em :lock-version 1}]})

(defn- repo-rico [ente]
  (fake-repo-sessoes (fn [_ id] (sessao-encerrada ente id)) chamada-rica))

;; ---------- B1: authz da rota (mesmo nivel de /quorum) ----------

(deftest b1-sem-token-401
  (let [repo-s (fake-repo-sessoes (fn [_ _] nil) (fn [_ _ _] {:presencas [] :justificativas []}))
        r (pt/response-for (service-fn* #{"secretario"} repo-s nil) :get (url (random-uuid)))]
    (is (= 401 (:status r)) "rota herda a cadeia de auth: sem token -> 401 (fail-closed)")))

(deftest b1-ator-sem-papel-de-secretario-le-a-composicao
  (let [ente (random-uuid) sid (random-uuid)
        r (pt/response-for (service-fn* #{} (repo-rico ente) roster-rico)
                           :get (url sid) :headers (com-auth (token ente (random-uuid))))]
    (is (= 200 (:status r))
        "o publico do telao (sem papel algum) le' a composicao, mesmo nivel de authz de /quorum")))

(deftest b1-sessao-de-outra-casa-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-aberta (random-uuid) id))
                                  (fn [_ _ _] {:presencas [] :justificativas []}))
        r (pt/response-for (service-fn* #{"secretario"} repo-s nil)
                           :get (url (random-uuid)) :headers (com-auth (token ente (random-uuid))))]
    (is (= 403 (:status r)) "sessao de ente alheio -> pode-ver-quorum-da-sessao? nega -> 403")))

(deftest b1-sessao-inexistente-no-tenant-404
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (fn [_ _ _] {:presencas [] :justificativas []}))
        r (pt/response-for (service-fn* #{"secretario"} repo-s nil)
                           :get (url (random-uuid)) :headers (com-auth (token ente (random-uuid))))]
    (is (= 404 (:status r)) "sessao que o repo nao acha no tenant do ator -> 404")))

(deftest b1-id-malformado-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (fn [_ _ _] {:presencas [] :justificativas []}))
        r (pt/response-for (service-fn* #{"secretario"} repo-s nil)
                           :get "/sessoes/nao-e-uuid/composicao" :headers (com-auth (token ente (random-uuid))))]
    (is (= 400 (:status r)) "sessao :id malformado no path -> 400, nunca 500")))

(deftest b1-sessao-sem-data-marcada-409-acionavel
  (let [ente (random-uuid) sid (random-uuid)
        sem-data {:id sid :ente-id ente :estado "agendada" :tipo-sessao "ordinaria"
                  :agendada-para nil :aberta-em nil :encerrada-em nil}
        repo-s (fake-repo-sessoes (fn [_ _] sem-data) (fn [_ _ _] {:presencas [] :justificativas []}))
        r (pt/response-for (service-fn* #{"secretario"} repo-s (fn [_ _] []))
                           :get (url sid) :headers (com-auth (token ente (random-uuid))))]
    (is (= 409 (:status r)))
    (is (re-find #"data" (:erro (ler-json r))))))

;; ---------- B1b: a FRONTEIRA DA SESSAO SECRETA — a rota nova carrega NOME, e ainda assim NAO pode ----------
;; ---------- afrouxar em relacao a /quorum: o gate tem de ser EXATAMENTE `pode-ver-quorum-da-sessao?`. -----

(deftest b1b-sessao-secreta-nega-quem-nao-e-secretario
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-secreta ente id)) chamada-rica)
        r (pt/response-for (service-fn* #{} repo-s roster-rico)
                           :get (url sid) :headers (com-auth (token ente (random-uuid))))]
    (is (= 403 (:status r))
        "sessao secreta + ator sem papel -> 403: a rota da composicao nao pode ser a porta dos fundos")))

(deftest b1b-mutacao-authz-cru-teria-deixado-passar
  ;; Nao e' um teste de comportamento do sistema — e' a PROVA POR MUTACAO que o briefing pede: se o
  ;; controller trocasse `logic/pode-ver-quorum-da-sessao?` por `logic/pode-ver-sessao?` cru, o teste
  ;; b1b-sessao-secreta-nega-quem-nao-e-secretario teria de ir a VERMELHO. Este teste documenta o oraculo:
  ;; `pode-ver-sessao?` cru so' olha o tenant, entao aceitaria a mesma requisicao que o teste acima nega.
  (let [ente (random-uuid) sid (random-uuid)
        sessao (sessao-secreta ente sid)
        ator {:ente-id ente :papeis #{}}]
    (is (false? (logic/pode-ver-quorum-da-sessao? ator sessao))
        "a politica REAL nega (mesma Casa, sem papel, sessao secreta)")
    (is (true? (logic/pode-ver-sessao? ator sessao))
        "a politica CRUA aceitaria — e' exatamente essa troca que o mutante do briefing simula")))

(deftest b1b-sessao-ordinaria-segue-aberta-ao-mesmo-ator
  (let [ente (random-uuid) sid (random-uuid)
        r (pt/response-for (service-fn* #{} (repo-rico ente) roster-rico)
                           :get (url sid) :headers (com-auth (token ente (random-uuid))))]
    (is (= 200 (:status r)) "sessao com transmissao publica + ator sem papel -> 200 (o telao resolve o nome)")))

(deftest b1b-sessao-secreta-continua-visivel-ao-secretario
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-secreta ente id)) chamada-rica)
        r (pt/response-for (service-fn* #{"secretario"} repo-s roster-rico)
                           :get (url sid) :headers (com-auth (token ente (random-uuid))))]
    (is (= 200 (:status r)) "o 'secretario' ja alcancava esse nome pela chamada nominal -- nao apertar aqui")))

;; ---------- B2: o TESTE CRUZADO — a composicao veio da MESMA aritmetica da chamada/quorum ----------

(deftest b2-cruzado-carimbos-identicos-aos-de-get-quorum
  (let [ente (random-uuid) sid (random-uuid) ident (random-uuid)
        svc (service-fn* #{"secretario"} (repo-rico ente) roster-rico)
        comp (ler-json (pt/response-for svc :get (url sid) :headers (com-auth (token ente ident))))
        quo  (ler-json (pt/response-for svc :get (str "/sessoes/" sid "/quorum")
                                        :headers (com-auth (token ente ident))))]
    (is (= (:sessao-id comp) (:sessao-id quo)))
    (is (= (:sessao-estado comp) (:sessao-estado quo)))
    (is (= (:data-de-composicao comp) (:data-de-composicao quo))
        "MESMA data de referencia -- as duas rotas resolvem a Casa pela MESMA `data-de-referencia`, nunca
        uma segunda leitura da composicao")))

;; ---------- B3: o PAYLOAD -- so' o que a docstring promete, e nada do que ela proibe ----------

(deftest b3-membros-tem-so-os-campos-permitidos
  (let [ente (random-uuid) sid (random-uuid)
        r (pt/response-for (service-fn* #{"secretario"} (repo-rico ente) roster-rico)
                           :get (url sid) :headers (com-auth (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= #{:sessao-id :sessao-estado :data-de-composicao :composicao-resolvida-em :membros}
           (set (keys body)))
        "o contrato de topo e' fechado: nem `instante`, nem `sem-registro-de-presenca`, nem `quorum` (isso e' /quorum)")
    (is (= 3 (count (:membros body))) "Ana, Bruno e Dario (o licenciado SEGUE no roster) -- so' o orfao fica de fora")
    (doseq [m (:membros body)]
      (is (= #{:vereador-id :nome-parlamentar :cargo-mesa} (set (keys m)))
          "cada membro e' SO vereador-id + nome-parlamentar + cargo-mesa -- nunca partido, nunca estado, nunca justificativa"))))

(deftest b3-payload-nao-carrega-o-que-e-proibido
  (let [ente (random-uuid) sid (random-uuid)
        r (pt/response-for (service-fn* #{"secretario"} (repo-rico ente) roster-rico)
                           :get (url sid) :headers (com-auth (token ente (random-uuid))))
        cru (:body r)]
    (is (not (re-find #"(?i)motivo|Internacao|cardiaca" cru))
        "o `motivo` da justificativa (LGPD) nao aparece em lugar nenhum do corpo")
    (is (not (re-find #"(?i)Silva Nascimento|Costa Ferreira|Melo Junior" cru))
        "nenhum NOME CIVIL atravessa esta rota -- so' o nome parlamentar")
    (is (not (re-find #"(?i)\"partido\"" cru))
        "`partido` nao esta no contrato desta rota (a rota publica de vereador tambem nao o serve)")
    (is (not (re-find #"(?i)\"estado\"|\"desde\"|\"fonte\"|\"registrado-em\"|\"registradoEm\"|inconsistencia" cru))
        "nenhum campo de ESTADO de presenca atravessa a rota da composicao -- so' /chamada e /quorum sabem disso")
    (is (re-find #"Ana Nascimento" cru) "o nome PARLAMENTAR de quem tem assento aparece")))

(deftest b3-vereador-sem-assento-nao-aparece-em-membros
  (let [ente (random-uuid) sid (random-uuid)
        r (pt/response-for (service-fn* #{"secretario"} (repo-rico ente) roster-rico)
                           :get (url sid) :headers (com-auth (token ente (random-uuid))))
        body (ler-json r)]
    (is (not-any? #(= (str v-orfao) (:vereador-id %)) (:membros body))
        "presenca sem assento conta no quorum, mas nao e' 'quem compoe a Casa' -- fica de fora de /composicao")))
