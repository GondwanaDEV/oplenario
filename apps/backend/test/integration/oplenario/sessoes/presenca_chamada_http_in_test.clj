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
            [oplenario.sessoes.components.repositorio :as repo-sessoes])
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
  "RepoSessoes fake (parcial): `buscar-sessao` resolve a sessao (para authz + resolver data/instante);
  `chamada-da-sessao` devolve {:presencas :justificativas} (o controller ja tem a sessao da 1a leitura)."
  [busca-fn chamada-fn]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ ente-id id] (busca-fn ente-id id))
    (chamada-da-sessao [_ ente-id id instante] (chamada-fn ente-id id instante))))

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
    (is (= {:presentes-plenario 0 :presentes-remoto 0 :membros-da-casa 2} (:quorum body)))
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
    (is (= {:estado "pendente" :motivo "Atestado medico"} (:justificativa l2)))
    (is (nil? (:justificativa l1)) "vereador sem justificativa -> nil, nunca omitido/erro")
    (is (= {:presentes-plenario 1 :presentes-remoto 0 :membros-da-casa 2} (:quorum body)))))

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
    (is (= #{:presentes-plenario :presentes-remoto :membros-da-casa} (set (keys (:quorum body))))
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

(deftest chamada-id-malformado-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (fn [_ _ _] {:presencas [] :justificativas []}))
        r (pt/response-for (service-fn* #{"secretario"} repo-s nil)
                           :get "/sessoes/nao-e-uuid/chamada" :headers (com-auth (token ente (random-uuid))))]
    (is (= 400 (:status r)) "sessao :id malformado no path -> 400, nunca 500")))
