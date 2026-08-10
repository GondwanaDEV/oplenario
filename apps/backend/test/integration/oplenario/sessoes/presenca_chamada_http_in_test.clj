(ns oplenario.sessoes.presenca-chamada-http-in-test
  "Fatia 1b-WIRE (§22.6 eixo C): a borda HTTP da CHAMADA. `GET /sessoes/:id/chamada` cruza o roster da Casa
  (`roster-da-casa`, seam sobre cadastros) com a presenca corrente + justificativas (`RepoSessoes/
  chamada-da-sessao`, uma so' tx) e devolve o estado derivado + quorum de cada vereador. Carrega a sessao
  (nil->404), pode-ver-sessao? (mesma Casa->403); papel exigido = 'secretario'. DB-free: RepoSessoes FAKE +
  RepoCadastros FAKE (so' `roster-da-casa`) + idp-dev real — espelha presenca_http_in_test."
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

(defn- sessao-aberta [ente-id id]
  {:id id :ente-id ente-id :estado "aberta" :tipo-sessao "ordinaria"
   :agendada-para agendada-para :aberta-em aberta-em :encerrada-em nil})

(defn- sessao-encerrada [ente-id id]
  {:id id :ente-id ente-id :estado "encerrada" :tipo-sessao "ordinaria"
   :agendada-para agendada-para :aberta-em aberta-em :encerrada-em encerrada-em})

(defn- fake-repo-sessoes
  "RepoSessoes fake (parcial): `chamada-da-sessao` e' a UNICA leitura da chamada e devolve
  {:sessao :instante :presencas :justificativas} — a sessao sai da MESMA leitura que a presenca (uma tx), e
  o `instante` e' resolvido DELA por `logic/instante-de-avaliacao`, como o Repo real faz por dentro da tx.
  `busca-fn` continua sendo a fonte da sessao (nil -> 404)."
  [busca-fn chamada-fn]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ ente-id id] (busca-fn ente-id id))
    (chamada-da-sessao [_ ente-id id agora]
      (when-let [s (busca-fn ente-id id)]
        (merge {:sessao s :instante (logic/instante-de-avaliacao s agora)}
               (chamada-fn ente-id id agora))))))

(defn- fake-repo-cadastros-roster
  "RepoCadastros fake (parcial): so' `roster-da-casa` — o unico metodo que o seam de `rotas.clj` exercita
  nesta borda (mesmo padrao de `fake-repo-cadastros` em presenca_http_in_test, que so' implementa
  `vereador-por-identidade`)."
  [roster-fn]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cadastros-comp/RepoCadastros
    (roster-da-casa [_ ente-id data] (roster-fn ente-id data))))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- service-fn*
  [papeis repo-s roster-fn]
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
(defn- url [sid] (str "/sessoes/" sid "/chamada"))

;; ---------- T13: sessao sem NENHUM evento — 200 honesto, nunca silencio ----------

(deftest t13-sem-eventos-todas-ausentes-e-sinalizador-ligado
  (let [ente (random-uuid) sid (random-uuid) v1 (random-uuid) v2 (random-uuid)
        roster-fn (fn [_ _] [{:vereador-id v1 :nome "Ana" :nome-parlamentar nil :partido "PDT"
                              :estado-mandato "vigente" :cargo-mesa nil}
                             {:vereador-id v2 :nome "Bruno" :nome-parlamentar "Bruno do Povo" :partido "PT"
                              :estado-mandato "vigente" :cargo-mesa "presidente"}])
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-aberta ente id))
                                  (fn [_ _ _] {:presencas [] :justificativas []}))
        r (pt/response-for (service-fn* #{"secretario"} repo-s roster-fn)
                           :get (url sid) :headers (com-auth (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)) "sessao sem nenhum evento -> 200, NUNCA 404/lista vazia/silencio")
    (is (true? (:sem-registro-de-presenca body)) "sinalizador ligado: a Casa nao tem NENHUM registro")
    (is (= 2 (count (:linhas body))))
    (is (every? #(= "ausente" (:estado %)) (:linhas body))
        "sem evento nenhum, todo vereador vigente deriva :ausente")
    (is (= "presidente" (:cargo-mesa (second (:linhas body)))) "cargo-mesa do roster passa para a linha")
    (is (= {:presentes-plenario 0 :presentes-remoto 0 :presentes-total 0 :membros-da-casa 2 :presencas-fora-do-roster 0}
           (:quorum body)))
    (is (some? (:data-de-composicao body)) "data-de-composicao presente")
    (is (some? (:composicao-resolvida-em body)) "composicao-resolvida-em presente")))

;; ---------- linha com presenca real + justificativa de OUTRO vereador ----------

(deftest chamada-com-presenca-e-justificativa
  (let [ente (random-uuid) sid (random-uuid) v1 (random-uuid) v2 (random-uuid)
        desde "2026-06-30T14:05:00Z" registrado "2026-06-30T14:06:00Z"
        roster-fn (fn [_ _] [{:vereador-id v1 :nome "Ana" :nome-parlamentar nil :partido "PDT"
                              :estado-mandato "vigente" :cargo-mesa nil}
                             {:vereador-id v2 :nome "Carla" :nome-parlamentar nil :partido "PSB"
                              :estado-mandato "vigente" :cargo-mesa nil}])
        repo-s (fake-repo-sessoes
                (fn [_ id] (sessao-aberta ente id))
                (fn [_ _ _]
                  {:presencas [{:vereador-id v1 :tipo "entrada" :modalidade "plenario"
                                :fonte "manual_secretaria" :ocorrido-em (Instant/parse desde)
                                :registrado-em (Instant/parse registrado)}]
                   :justificativas [{:id (random-uuid) :vereador-id v2 :estado "pendente"
                                      :motivo "Atestado medico" :decidido-por nil :decidido-em nil
                                      :lock-version 0}]}))
        r (pt/response-for (service-fn* #{"secretario"} repo-s roster-fn)
                           :get (url sid) :headers (com-auth (token ente (random-uuid))))
        body (ler-json r)
        [l1 l2] (:linhas body)]
    (is (= 200 (:status r)))
    (is (false? (:sem-registro-de-presenca body)) "ha' pelo menos um evento na sessao")
    (is (= "presente-plenario" (:estado l1)))
    (is (= desde (:desde l1)))
    (is (= "manual_secretaria" (:fonte l1)))
    (is (= registrado (:registrado-em l1)))
    (is (= "ausente-justificativa-pendente" (:estado l2))
        "justificativa pendente e' um estado PROPRIO, nao :ausente")
    (is (= {:estado "pendente" :motivo "Atestado medico" :decidido-em nil} (:justificativa l2))
        "pendente -> `decidido-em` nil (espelha o CHECK justificativa_decisao_coerente)")
    (is (nil? (:justificativa l1)) "vereador sem justificativa -> nil, nunca omitido/erro")
    (is (= {:presentes-plenario 1 :presentes-remoto 0 :presentes-total 1 :membros-da-casa 2 :presencas-fora-do-roster 0}
           (:quorum body)))))

;; ---------- REVISAO Etapa 1 (MAJOR): evento de quem nao esta no roster NAO some da resposta ----------

(deftest chamada-publica-a-presenca-sem-assento-em-vez-de-descarta-la
  (let [ente (random-uuid) sid (random-uuid) dentro (random-uuid) fora (random-uuid)
        roster-fn (fn [_ _] [{:vereador-id dentro :nome "Ana" :nome-parlamentar nil :partido "PDT"
                              :estado-mandato "vigente" :cargo-mesa nil}])
        repo-s (fake-repo-sessoes
                (fn [_ id] (sessao-aberta ente id))
                (fn [_ _ _]
                  {:presencas [{:vereador-id dentro :tipo "entrada" :modalidade "plenario"
                                :fonte "manual_secretaria" :ocorrido-em aberta-em :registrado-em aberta-em}
                               {:vereador-id fora :tipo "entrada" :modalidade "plenario"
                                :fonte "manual_secretaria" :ocorrido-em aberta-em :registrado-em aberta-em}]
                   :justificativas []}))
        r (pt/response-for (service-fn* #{"secretario"} repo-s roster-fn)
                           :get (url sid) :headers (com-auth (token ente (random-uuid))))
        body (ler-json r)
        orfa (first (filter :sem-assento (:linhas body)))]
    (is (= 200 (:status r)))
    (is (= 2 (count (:linhas body))) "o evento de quem nao tem cadeira PRODUZ linha, nao vira descarte mudo")
    (is (= (str fora) (:vereador-id orfa)))
    (is (= "presente-plenario" (:estado orfa)))
    (is (true? (:inconsistencia-cadastro orfa)))
    (is (nil? (:nome orfa)) "identidade desconhecida -> nil honesto, nunca um nome inventado")
    (is (= {:presentes-plenario 2 :presentes-remoto 0 :presentes-total 2 :membros-da-casa 1 :presencas-fora-do-roster 1}
           (:quorum body))
        "numerador = o que o motor conta (2); denominador = so' as cadeiras (1); e o desvio e' PUBLICADO")))

;; ---------- T14: multi-tenant ----------

(deftest t14-sessao-inexistente-no-tenant-404
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (fn [_ _ _] {:presencas [] :justificativas []}))
        r (pt/response-for (service-fn* #{"secretario"} repo-s nil)
                           :get (url (random-uuid)) :headers (com-auth (token ente (random-uuid))))]
    (is (= 404 (:status r)) "sessao de OUTRA Casa (RLS/repo nao acha) -> 404, nem chega a carregar")))

(deftest t14-sessao-de-outra-casa-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-aberta (random-uuid) id))
                                  (fn [_ _ _] {:presencas [] :justificativas []}))
        r (pt/response-for (service-fn* #{"secretario"} repo-s nil)
                           :get (url (random-uuid)) :headers (com-auth (token ente (random-uuid))))]
    (is (= 403 (:status r)) "sessao carregada mas de ente alheio -> pode-ver-sessao? nega -> 403")))

;; ---------- T15: sessao inexistente (mesmo tenant) ----------

(deftest t15-sessao-inexistente-404
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (fn [_ _ _] {:presencas [] :justificativas []}))
        r (pt/response-for (service-fn* #{"secretario"} repo-s nil)
                           :get (url (random-uuid)) :headers (com-auth (token ente (random-uuid))))]
    (is (= 404 (:status r)))))

;; ---------- T16: contrato — ChamadaOut :closed + campos obrigatorios nao-nulos ----------

(deftest t16-contrato-campos-de-composicao-presentes-e-nao-nulos
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-encerrada ente id))
                                  (fn [_ _ _] {:presencas [] :justificativas []}))
        r (pt/response-for (service-fn* #{"secretario"} repo-s (fn [_ _] []))
                           :get (url sid) :headers (com-auth (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= (str sid) (:sessao-id body)))
    (is (= "encerrada" (:sessao-estado body)))
    (is (= "2026-06-30T18:00:00Z" (:instante body)) "sessao encerrada -> instante CONGELADO em encerrada-em")
    (is (string? (:data-de-composicao body)) "data-de-composicao presente e nao-nula")
    (is (string? (:composicao-resolvida-em body)) "composicao-resolvida-em presente e nao-nula")
    (is (= #{:presentes-plenario :presentes-remoto :presentes-total :membros-da-casa :presencas-fora-do-roster}
           (set (keys (:quorum body))))
        "quorum e' o shape fechado esperado")))

;; ---------- T17: authz da rota ----------

(deftest t17-sem-token-401
  (let [repo-s (fake-repo-sessoes (fn [_ _] nil) (fn [_ _ _] {:presencas [] :justificativas []}))
        r (pt/response-for (service-fn* #{"secretario"} repo-s nil) :get (url (random-uuid)))]
    (is (= 401 (:status r)) "rota herda a cadeia de auth: sem token -> 401 (fail-closed)")))

(deftest t17-papel-vereador-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (fn [_ _ _] {:presencas [] :justificativas []}))
        r (pt/response-for (service-fn* #{"vereador"} repo-s nil)
                           :get (url (random-uuid)) :headers (com-auth (token ente (random-uuid))))]
    (is (= 403 (:status r)) "ator sem papel 'secretario' -> authz grossa nega -> 403 (nunca chega ao controller)")))

;; ---------- REVISAO Etapa 1 (MAJOR): sessao agendada SEM data marcada nao pode virar 500 ----------
;; `wire/in/AgendarSessao` declara `agendada-para` OPCIONAL e a coluna e' nullable (mig 0026): POST /sessoes
;; sem data e' um caminho NORMAL da API. A chamada dessa sessao caia em `ex-info :servidor/erro` -> 500
;; "erro interno" — a secretaria que agendou nao consegue abrir a chamada e nao recebe indicacao nenhuma.

(deftest chamada-de-sessao-agendada-sem-data-marcada-409-acionavel
  (let [ente (random-uuid) sid (random-uuid)
        sem-data {:id sid :ente-id ente :estado "agendada" :tipo-sessao "ordinaria"
                  :agendada-para nil :aberta-em nil :encerrada-em nil}
        repo-s (fake-repo-sessoes (fn [_ _] sem-data) (fn [_ _ _] {:presencas [] :justificativas []}))
        r (pt/response-for (service-fn* #{"secretario"} repo-s (fn [_ _] []))
                           :get (url sid) :headers (com-auth (token ente (random-uuid))))]
    (is (= 409 (:status r)) "sessao sem marco temporal nenhum -> 409 acionavel, NUNCA 500 generico")
    (is (re-find #"data" (:erro (ler-json r)))
        "a mensagem diz o que fazer (marcar a data), em vez de 'erro interno'")))

(deftest chamada-id-malformado-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (fn [_ _ _] {:presencas [] :justificativas []}))
        r (pt/response-for (service-fn* #{"secretario"} repo-s nil)
                           :get "/sessoes/nao-e-uuid/chamada" :headers (com-auth (token ente (random-uuid))))]
    (is (= 400 (:status r)) "sessao :id malformado no path -> 400, nunca 500")))

;; ---------- REGRESSAO: a chamada de uma sessao ENCERRADA muda quando a Mesa decide depois ----------

(deftest justificativa-decidida-depois-expoe-quando-foi-decidida
  ;; MEDIO. `chamada-da-sessao` CONGELA o instante da presenca mas le' a justificativa no estado CORRENTE —
  ;; e' o efeito desejado (a Mesa aprecia a falta dias depois da sessao). Sem `decidido-em`, porem, a leitura
  ;; de uma sessao encerrada mudava de conteudo ao longo do tempo sem nenhum sinal: a ata impressa de 12/03
  ;; dizia "ausente", a tela de 21/03 dizia "ausente-justificado", com o MESMO `instante` congelado, e o
  ;; juridico nao tinha como saber qual das duas envelheceu. O dado ja vinha do Repo e era descartado na
  ;; projecao.
  (let [ente (random-uuid) sid (random-uuid) v (random-uuid)
        decidido "2026-03-20T18:00:00Z"
        roster-fn (fn [_ _] [{:vereador-id v :nome "Ana" :nome-parlamentar nil :partido "PDT"
                              :estado-mandato "vigente" :cargo-mesa nil}])
        repo-s (fake-repo-sessoes
                (fn [_ id] (sessao-aberta ente id))
                (fn [_ _ _]
                  {:presencas []
                   :justificativas [{:id (random-uuid) :vereador-id v :estado "aprovada"
                                     :motivo "Atestado medico" :decidido-por (random-uuid)
                                     :decidido-em (Instant/parse decidido) :lock-version 1}]}))
        r (pt/response-for (service-fn* #{"secretario"} repo-s roster-fn)
                           :get (url sid) :headers (com-auth (token ente (random-uuid))))
        linha (first (:linhas (ler-json r)))]
    (is (= 200 (:status r)))
    (is (= "ausente-justificado" (:estado linha)))
    (is (= decidido (:decidido-em (:justificativa linha)))
        "a resposta diz QUANDO a Mesa decidiu — e' o que reconcilia a tela com a ata impressa")))
