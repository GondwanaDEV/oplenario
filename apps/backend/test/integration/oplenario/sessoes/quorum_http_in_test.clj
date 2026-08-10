(ns oplenario.sessoes.quorum-http-in-test
  "Etapa 4a (§22.6 eixo C): a leitura MAGRA de quorum, `GET /sessoes/:id/quorum`.

  EXISTE POR AUTHZ, nao por performance. `GET /sessoes/:id/chamada` exige o papel 'secretario' na borda —
  e' a ferramenta operacional da Mesa de conducao, e a sua resposta carrega linha NOMINAL + o `motivo` da
  justificativa (que pode ser dado de saude, LGPD). Mas o painel do plenario (o TELAO) abre pelo SSE
  `GET /sessoes/:id/plenario`, que NAO exige papel nenhum: qualquer ator autenticado da mesma Casa numa
  sessao com transmissao publica passa. Quem ve o telao, portanto, tomava 403 na unica rota que sabe o
  DENOMINADOR — e o telao mostrava 'N presentes' sem 'de M'.

  Esta rota fecha a lacuna sem ampliar o alcance do dado pessoal: SO' os numeros, o mesmo nivel de authz do
  irmao `GET /sessoes/:id` (auth + `pode-ver-sessao?`, sem papel), e os numeros vem do MESMO controller e do
  MESMO `logic/contar-quorum` — nunca de uma segunda aritmetica de composicao da Casa.

  DB-free: RepoSessoes FAKE + RepoCadastros FAKE (so' `roster-da-casa`) + idp-dev real — espelha
  presenca_chamada_http_in_test, de onde os fakes sao copiados deliberadamente (o teste CRUZADO exige que as
  duas rotas leiam exatamente o mesmo estado)."
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

;; `transmite-publica` NAO e' decoracao na fixture: e' a coluna que `tempo-real/logic/plenario-publico?` le'
;; para recusar a SUBSCRICAO INTEIRA do painel numa sessao secreta, e desde a revisao desta branch e' tambem
;; o que `logic/pode-ver-quorum-da-sessao?` le'. Uma fixture que a omitisse deixaria o gate novo verde por
;; acidente (`(true? nil)` = false), que e' o padrao ja' registrado no projeto como "fixture com vocabulario
;; ficticio". Vem da migration 0026 (`sessoes.sessao.transmite_publica`), via `db/sessao/colunas`.
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
(defn- url [sid] (str "/sessoes/" sid "/quorum"))
(defn- url-chamada [sid] (str "/sessoes/" sid "/chamada"))

;; Um estado nao-trivial de proposito: dois presentes no plenario, um remoto, um licenciado (fora do
;; denominador), um justificado, e uma presenca SEM ASSENTO (fora do roster — conta no numerador, nao no
;; denominador). E' exatamente o caso em que uma segunda aritmetica de quorum divergiria da primeira.
(def ^:private v-ana (random-uuid))
(def ^:private v-bruno (random-uuid))
(def ^:private v-carla (random-uuid))
(def ^:private v-licenciado (random-uuid))
(def ^:private v-orfao (random-uuid))

(defn- roster-rico [_ _]
  [{:vereador-id v-ana :nome "Ana" :nome-parlamentar nil :partido "PDT"
    :estado-mandato "vigente" :cargo-mesa "presidente"}
   {:vereador-id v-bruno :nome "Bruno" :nome-parlamentar nil :partido "PT"
    :estado-mandato "vigente" :cargo-mesa nil}
   {:vereador-id v-carla :nome "Carla" :nome-parlamentar nil :partido "PSB"
    :estado-mandato "vigente" :cargo-mesa nil}
   {:vereador-id v-licenciado :nome "Dario" :nome-parlamentar nil :partido "MDB"
    :estado-mandato "licenciado" :cargo-mesa nil}])

(defn- chamada-rica [_ _ _]
  {:presencas [{:vereador-id v-ana :tipo "entrada" :modalidade "plenario"
                :fonte "manual_secretaria" :ocorrido-em aberta-em :registrado-em aberta-em}
               {:vereador-id v-bruno :tipo "entrada" :modalidade "plenario"
                :fonte "manual_secretaria" :ocorrido-em aberta-em :registrado-em aberta-em}
               {:vereador-id v-carla :tipo "entrada" :modalidade "remoto"
                :fonte "autoatendimento" :ocorrido-em aberta-em :registrado-em aberta-em}
               {:vereador-id v-orfao :tipo "entrada" :modalidade "plenario"
                :fonte "manual_secretaria" :ocorrido-em aberta-em :registrado-em aberta-em}]
   ;; O `motivo` e' dado de saude de proposito: se ele vazar para esta rota, o teste B3 acusa.
   ;; "aprovada", nao "deferida": o vocabulario vem de `logic/estados-justificativa` (o CHECK da mig 0029 o
   ;; espelha), nunca redigitado de memoria — fixture que inventa vocabulario mantem read-model morto verde.
   :justificativas [{:id (random-uuid) :vereador-id v-licenciado :estado "aprovada"
                     :motivo "Internacao hospitalar - cirurgia cardiaca" :decidido-por (random-uuid)
                     :decidido-em encerrada-em :lock-version 1}]})

(defn- repo-rico [ente]
  (fake-repo-sessoes (fn [_ id] (sessao-encerrada ente id)) chamada-rica))

;; ---------- B1: authz da rota ----------

(deftest b1-sem-token-401
  (let [repo-s (fake-repo-sessoes (fn [_ _] nil) (fn [_ _ _] {:presencas [] :justificativas []}))
        r (pt/response-for (service-fn* #{"secretario"} repo-s nil) :get (url (random-uuid)))]
    (is (= 401 (:status r)) "rota herda a cadeia de auth: sem token -> 401 (fail-closed)")))

(deftest b1-ator-sem-papel-de-secretario-le-o-quorum-mas-nao-a-chamada
  ;; O CORACAO da fatia. O publico do telao (Presidente da Mesa, vereador, operador de som) nao tem
  ;; necessariamente o papel 'secretario'. Se as duas rotas exigissem o mesmo papel, ou o telao ficava sem
  ;; denominador, ou o `motivo` da justificativa (LGPD) passava a circular para quem so' queria contar
  ;; cabecas. As duas coisas sao inaceitaveis — dai a rota magra.
  (let [ente (random-uuid) sid (random-uuid) ident (random-uuid)
        svc (service-fn* #{} (repo-rico ente) roster-rico)
        r-quorum  (pt/response-for svc :get (url sid) :headers (com-auth (token ente ident)))
        r-chamada (pt/response-for svc :get (url-chamada sid) :headers (com-auth (token ente ident)))]
    (is (= 403 (:status r-chamada))
        "a chamada NOMINAL segue exigindo 'secretario' na borda — esta fatia nao afrouxa nada")
    (is (= 200 (:status r-quorum))
        "o quorum MAGRO abre para quem ve o telao: mesmo nivel de authz de GET /sessoes/:id")
    (is (= 3 (get-in (ler-json r-quorum) [:quorum :membros-da-casa]))
        "e o denominador chega de fato — e' a razao inteira de a rota existir")))

(deftest b1-sessao-de-outra-casa-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-aberta (random-uuid) id))
                                  (fn [_ _ _] {:presencas [] :justificativas []}))
        r (pt/response-for (service-fn* #{"secretario"} repo-s nil)
                           :get (url (random-uuid)) :headers (com-auth (token ente (random-uuid))))]
    (is (= 403 (:status r)) "sessao carregada mas de ente alheio -> pode-ver-sessao? nega -> 403")))

(deftest b1-sessao-inexistente-no-tenant-404
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (fn [_ _ _] {:presencas [] :justificativas []}))
        r (pt/response-for (service-fn* #{"secretario"} repo-s nil)
                           :get (url (random-uuid)) :headers (com-auth (token ente (random-uuid))))]
    (is (= 404 (:status r)) "sessao que o repo nao acha no tenant do ator -> 404, nem chega a carregar")))

;; ---------- B1b: a FRONTEIRA DA SESSAO SECRETA (revisao adversarial, MAJOR/security) ----------
;; A Etapa 4a justificou o nivel de authz desta rota pelo publico do TELAO, mas copiou so' METADE da politica
;; do telao: ficou a parte que ABRE (sem exigir papel) e caiu a parte que FECHA. O SSE
;; `GET /sessoes/:id/plenario` roda `tempo-real/logic/pode-assistir-plenario?` = mesma-casa? AND
;; plenario-publico?, e `tempo_real/canais.clj` grava a regra em prosa: "se transmite_publica=false, RECUSAR
;; a subscricao (403) — nao filtrar por evento". Antes da Etapa 4a, o estado de presenca de uma sessao
;; SECRETA so' era alcancavel por quem tinha o papel 'secretario' (via `/chamada`); depois dela, passou a ser
;; alcancavel por QUALQUER vinculo ativo da Casa, que num rito de cassacao reconstroi a serie temporal do
;; quorum (obstrucao, acordo fechado) por polling. Os tres testes abaixo cravam a politica inteira.

(deftest b1b-sessao-secreta-nega-quem-nao-e-secretario
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-secreta ente id)) chamada-rica)
        r (pt/response-for (service-fn* #{} repo-s roster-rico)
                           :get (url sid) :headers (com-auth (token ente (random-uuid))))]
    (is (= 403 (:status r))
        "sessao secreta + ator sem papel -> 403, exatamente como no SSE do painel (a rota nova nao pode ser a porta dos fundos)")))

(deftest b1b-sessao-ordinaria-segue-aberta-ao-mesmo-ator
  ;; A prova de que o aperto acima nao matou a fatia: o MESMO ator sem papel, numa sessao com transmissao
  ;; publica, continua lendo o quorum — que e' a razao inteira de a rota existir.
  (let [ente (random-uuid) sid (random-uuid)
        r (pt/response-for (service-fn* #{} (repo-rico ente) roster-rico)
                           :get (url sid) :headers (com-auth (token ente (random-uuid))))]
    (is (= 200 (:status r)) "sessao com transmissao publica + ator sem papel -> 200 (o telao segue funcionando)")))

(deftest b1b-sessao-secreta-continua-visivel-ao-secretario
  ;; O 'secretario' ja alcancava esse estado por `/chamada` (rota NOMINAL) antes da Etapa 4a — negar-lhe a
  ;; leitura MAGRA seria apertar mais que a linha de base, sem ganho de sigilo nenhum.
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-secreta ente id)) chamada-rica)
        r (pt/response-for (service-fn* #{"secretario"} repo-s roster-rico)
                           :get (url sid) :headers (com-auth (token ente (random-uuid))))]
    (is (= 200 (:status r)) "a Mesa/secretaria conduz a chamada da sessao secreta — o gate e' de publico, nao de sessao")))

(deftest b1-id-malformado-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (fn [_ _ _] {:presencas [] :justificativas []}))
        r (pt/response-for (service-fn* #{"secretario"} repo-s nil)
                           :get "/sessoes/nao-e-uuid/quorum" :headers (com-auth (token ente (random-uuid))))]
    (is (= 400 (:status r)) "sessao :id malformado no path -> 400, nunca 500")))

(deftest b1-sessao-sem-data-marcada-409-acionavel
  ;; Mesmo 409 acionavel do GET /chamada: a rota magra reusa o MESMO controller, entao herda a mesma recusa
  ;; (e nao um 500 generico que deixaria a secretaria sem saber que falta marcar a data).
  (let [ente (random-uuid) sid (random-uuid)
        sem-data {:id sid :ente-id ente :estado "agendada" :tipo-sessao "ordinaria"
                  :agendada-para nil :aberta-em nil :encerrada-em nil}
        repo-s (fake-repo-sessoes (fn [_ _] sem-data) (fn [_ _ _] {:presencas [] :justificativas []}))
        r (pt/response-for (service-fn* #{"secretario"} repo-s (fn [_ _] []))
                           :get (url sid) :headers (com-auth (token ente (random-uuid))))]
    (is (= 409 (:status r)))
    (is (re-find #"data" (:erro (ler-json r))))))

;; ---------- B2: o TESTE CRUZADO — uma so' aritmetica de quorum ----------

(deftest b2-cruzado-os-numeros-sao-identicos-aos-de-get-chamada
  ;; A regra que a Etapa 1 e a Etapa 2 gastaram uma revisao cada para cravar: a MESMA sessao nao pode ter
  ;; duas aritmeticas de composicao da Casa. Sessao ENCERRADA de proposito — `instante` congelado em
  ;; `encerrada-em` torna as duas leituras comparaveis campo a campo, sem a deriva de 'agora'.
  (let [ente (random-uuid) sid (random-uuid) ident (random-uuid)
        svc (service-fn* #{"secretario"} (repo-rico ente) roster-rico)
        q (ler-json (pt/response-for svc :get (url sid) :headers (com-auth (token ente ident))))
        c (ler-json (pt/response-for svc :get (url-chamada sid) :headers (com-auth (token ente ident))))]
    (is (= (:quorum c) (:quorum q))
        "MESMO controller, MESMO logic/contar-quorum — os numeros sao identicos por construcao")
    (is (= {:presentes-plenario 3 :presentes-remoto 1 :presentes-total 4 :membros-da-casa 3 :presencas-fora-do-roster 1}
           (:quorum q))
        "e sao os numeros CERTOS: licenciado fora do denominador, orfao no numerador e sinalizado")
    (is (= (:sem-registro-de-presenca c) (:sem-registro-de-presenca q)))
    (is (= (:instante c) (:instante q)) "o instante de avaliacao e' o mesmo (sessao encerrada -> congelado)")
    (is (= (:sessao-estado c) (:sessao-estado q)))
    (is (= (:data-de-composicao c) (:data-de-composicao q)))))

(deftest b2-cruzado-sessao-sem-nenhum-evento
  ;; O caso que o telao encontra ANTES de a chamada comecar: zero presentes, denominador cheio. As duas
  ;; rotas tem de concordar tambem aqui (e o sinalizador honesto tem de viajar na rota magra).
  (let [ente (random-uuid) sid (random-uuid) ident (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-encerrada ente id))
                                  (fn [_ _ _] {:presencas [] :justificativas []}))
        svc (service-fn* #{"secretario"} repo-s roster-rico)
        q (ler-json (pt/response-for svc :get (url sid) :headers (com-auth (token ente ident))))
        c (ler-json (pt/response-for svc :get (url-chamada sid) :headers (com-auth (token ente ident))))]
    (is (= (:quorum c) (:quorum q)))
    (is (= {:presentes-plenario 0 :presentes-remoto 0 :presentes-total 0 :membros-da-casa 3 :presencas-fora-do-roster 0}
           (:quorum q)))
    (is (true? (:sem-registro-de-presenca q))
        "o telao precisa distinguir 'a Casa faltou' de 'ninguem registrou nada ainda'")))

;; ---------- B3: prova de NAO-VAZAMENTO (afirma sobre as CHAVES, nunca sobre os valores) ----------

(deftest b3-payload-nao-carrega-linha-nominal-nem-justificativa
  (let [ente (random-uuid) sid (random-uuid)
        r (pt/response-for (service-fn* #{"secretario"} (repo-rico ente) roster-rico)
                           :get (url sid) :headers (com-auth (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)))
    ;; Igualdade de CONJUNTO, nao `not-contains`: um campo novo acrescentado no futuro quebra este teste de
    ;; proposito, obrigando quem o acrescentar a decidir explicitamente se ele pode circular no telao.
    (is (= #{:sessao-id :sessao-estado :instante :data-de-composicao :composicao-resolvida-em
             :sem-registro-de-presenca :quorum}
           (set (keys body)))
        "o contrato e' fechado e MAGRO: so' os numeros e os carimbos que os situam no tempo")
    (is (= #{:presentes-plenario :presentes-remoto :presentes-total :membros-da-casa :presencas-fora-do-roster}
           (set (keys (:quorum body))))
        "o bloco de quorum e' o MESMO ChamadaQuorumOut, sem acrescimo")
    (is (nil? (:linhas body)) "nenhuma linha NOMINAL atravessa esta rota")
    (is (nil? (:chamadas-conduzidas body)) "nem o ato de conducao (carrega `conduzida-por`)")
    ;; Rede de seguranca sobre o BODY CRU: se um dia alguem aninhar nome/motivo dentro de um campo novo, a
    ;; asserção de chaves acima pega o campo, e esta pega o conteudo.
    (let [cru (:body r)]
      (is (not (re-find #"(?i)motivo|Internacao|cardiaca" cru))
          "o `motivo` da justificativa (LGPD — pode ser dado de saude) nao aparece em lugar nenhum do corpo")
      (is (not (re-find #"(?i)Ana|Bruno|Carla|Dario" cru))
          "nenhum nome de vereador atravessa a rota do telao")
      (is (not (re-find (re-pattern (str v-ana)) cru))
          "nem o vereador-id — a rota magra conta cabecas, nao as identifica"))))
