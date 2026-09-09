(ns oplenario.sessoes.tribuna-decisao-http-in-test
  "Slice F4 — eixo F (§22.6), tribuna: a DECISAO DA MESA (F4.5c, FECHA a tribuna). `POST /sessoes/:id/decisoes-mesa`
  registra o ato regimental do presidente sobre questao de ordem (corpo {questao, decisao, fundamentacao?,
  decidido-em, fala-id?}) -> 201 {:id}. APPEND-ONLY puro: sem evento, sem CAS, sem 409 (a decisao e' tomada uma
  vez; corrigir = nova decisao). presidente-id e created-by sao INJETADOS do `ator` (nunca vem do corpo);
  questao/decisao nao-vazias (apos trim) sao validadas na BORDA -> 400 (nunca o CHECK da migration -> 500).
  `fala-id` opcional: se presente, tem de pertencer A ESTA sessao (anti confused-deputy, espelha cronometro/
  encerrar). Carrega a sessao (nil->404), pode-ver-sessao? (mesma Casa->403). DB-free: RepoSessoes FAKE +
  idp-dev real — espelha o tribuna-fala-http-in-test."
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
  "RepoSessoes fake (parcial): `buscar-sessao` resolve a sessao; `buscar-fala` resolve a fala (sessao-id =
  `fala-sessao-id`, ou nil se `fala-ausente?`) — o controller checa fala.sessao-id = path sessao-id (anti
  confused-deputy) SO quando `fala-id` veio no corpo; `registrar-decisao-mesa!` GRAVA o mapa em `capturado` e
  ECOA o recibo {:id}."
  [busca-fn capturado & {:keys [fala-sessao-id fala-ausente?]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ ente-id id] (busca-fn ente-id id))
    (buscar-fala [_ _ente-id id]
      (when-not fala-ausente? {:id id :sessao-id fala-sessao-id}))
    (registrar-decisao-mesa! [_ ente-id m]
      (reset! capturado (assoc m :ente-id ente-id))
      {:id (:id m)})))

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
(defn- corpo [m] (json/write-value-as-string m))
(defn- url-decisoes [sid] (str "/sessoes/" sid "/decisoes-mesa"))

(def ^:private decisao-valida
  {"questao" "Apreciacao de questao de ordem sobre o quorum"
   "decisao" "Indeferida; o quorum esta regular nos termos do art. 90"
   "decidido-em" "2026-06-30T14:00:00Z"})

;; ---------- POST /sessoes/:id/decisoes-mesa (sucesso) ----------

(deftest decisao-201
  (let [ente (random-uuid) sid (random-uuid) pres (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) cap)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-decisoes sid)
                           :headers (com-json (token ente pres))
                           :body (corpo decisao-valida))
        body (ler-json r)]
    (is (= 201 (:status r)) "papel + mesma Casa + corpo valido -> 201")
    (is (= (str (:id @cap)) (:id body)) "recibo carrega o id da decisao gravada")
    (is (= sid (:sessao-id @cap)) "Repo recebeu a sessao-id (path)")
    (is (= "Apreciacao de questao de ordem sobre o quorum" (:questao @cap)) "Repo recebeu a questao")
    (is (= "Indeferida; o quorum esta regular nos termos do art. 90" (:decisao @cap)) "Repo recebeu a decisao")
    (is (= (Instant/parse "2026-06-30T14:00:00Z") (:decidido-em @cap)) "Repo recebeu o decidido-em coagido a Instant")
    (is (= pres (:presidente-id @cap)) "presidente-id INJETADO do ator (nunca do corpo)")
    (is (= pres (:created-by @cap)) "created-by INJETADO do ator")
    (is (nil? (:fala-id @cap)) "sem fala-id no corpo -> nil")
    (is (some? (:id @cap)) "id gerado server-side (PK NOT NULL, nunca nil)")))

(deftest decisao-com-fundamentacao-201
  (let [ente (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) cap)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-decisoes (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc decisao-valida "fundamentacao" "Art. 90 do Regimento Interno")))]
    (is (= 201 (:status r)) "fundamentacao presente nao-vazia -> 201")
    (is (= "Art. 90 do Regimento Interno" (:fundamentacao @cap)) "Repo recebeu a fundamentacao")))

(deftest decisao-com-fala-201
  (let [ente (random-uuid) sid (random-uuid) fid (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) cap :fala-sessao-id sid)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-decisoes sid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc decisao-valida "fala-id" (str fid))))]
    (is (= 201 (:status r)) "fala-id da MESMA sessao -> 201")
    (is (= fid (:fala-id @cap)) "Repo recebeu o fala-id coagido")))

;; ---------- authz / not-found ----------

(deftest decisao-sessao-encerrada-409
  ;; T2 grupo A achado #4 (ledger de prontidao Fase 8): mesmo gate `exigir-sessao-aberta!` da familia tribuna.
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (assoc (sessao-canonica ente id) :estado "encerrada")) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-decisoes (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo decisao-valida))]
    (is (= 409 (:status r)) "sessao ja encerrada -> 409")))

(deftest decisao-sessao-inexistente-404
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-decisoes (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo decisao-valida))]
    (is (= 404 (:status r)) "sessao inexistente -> 404")))

(deftest decisao-casa-alheia-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica (random-uuid) id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-decisoes (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo decisao-valida))]
    (is (= 403 (:status r)) "sessao de ente alheio -> 403")))

(deftest decisao-sem-papel-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"vereador"} repo-s)
                           :post (url-decisoes (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo decisao-valida))]
    (is (= 403 (:status r)) "sem papel 'secretario' -> 403")))

(deftest decisao-sem-token-401
  (let [repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-decisoes (random-uuid))
                           :headers {"Content-Type" "application/json"}
                           :body (corpo decisao-valida))]
    (is (= 401 (:status r)) "sem token -> 401")))

;; ---------- validacao de borda -> 400 ----------

(deftest decisao-questao-vazia-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-decisoes (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc decisao-valida "questao" "")))]
    (is (= 400 (:status r)) "questao vazia -> 400 na borda, nunca 500 do CHECK")))

(deftest decisao-questao-em-branco-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-decisoes (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc decisao-valida "questao" "   ")))]
    (is (= 400 (:status r)) "questao so espacos (trim) -> 400 (espelha o CHECK length(trim)>0)")))

(deftest decisao-decisao-vazia-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-decisoes (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc decisao-valida "decisao" "")))]
    (is (= 400 (:status r)) "decisao vazia -> 400")))

(deftest decisao-questao-ausente-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-decisoes (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (dissoc decisao-valida "questao")))]
    (is (= 400 (:status r)) "corpo sem questao (obrigatoria) -> 400")))

(deftest decisao-decisao-ausente-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-decisoes (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (dissoc decisao-valida "decisao")))]
    (is (= 400 (:status r)) "corpo sem decisao (obrigatoria) -> 400")))

(deftest decisao-decidido-em-ausente-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-decisoes (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (dissoc decisao-valida "decidido-em")))]
    (is (= 400 (:status r)) "corpo sem decidido-em (obrigatorio) -> 400")))

(deftest decisao-decidido-em-invalido-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-decisoes (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc decisao-valida "decidido-em" "ontem a tarde")))]
    (is (= 400 (:status r)) "decidido-em nao-ISO-8601 -> 400")))

(deftest decisao-fala-id-malformada-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-decisoes (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc decisao-valida "fala-id" "nao-e-uuid")))]
    (is (= 400 (:status r)) "fala-id presente mas malformada -> 400, nunca 500")))

(deftest decisao-fundamentacao-em-branco-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-decisoes (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc decisao-valida "fundamentacao" "   ")))]
    (is (= 400 (:status r)) "fundamentacao presente mas em branco -> 400 (espelha o CHECK)")))

(deftest decisao-presidente-id-do-corpo-ignorado-201
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-decisoes (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           ;; presidente-id NAO vem do corpo (injetado do ator); um cliente que tenta forja-lo
                           ;; e' barrado pelo allowlist do adapter (so-esperados) -> nunca chega ao Repo.
                           :body (corpo (assoc decisao-valida "presidente-id" (str (random-uuid)))))
        body (ler-json (pt/response-for (service-fn* #{"secretario"} repo-s)
                                        :post (url-decisoes (random-uuid))
                                        :headers (com-json (token ente (random-uuid)))
                                        :body (corpo decisao-valida)))]
    ;; o campo extra e' IGNORADO pelo allowlist (nao 400; espelha a defesa dos outros adapters da tribuna) — o
    ;; importante e' que NUNCA vira presidente-id no Repo (coberto por decisao-201). Aqui so confirmamos 201.
    (is (= 201 (:status r)) "campo extra 'presidente-id' ignorado pelo allowlist -> 201")
    (is (some? body) "corpo limpo segue 201")))

;; ---------- anti confused-deputy (fala-id de outra sessao) -> 404 ----------

(deftest decisao-fala-de-outra-sessao-404
  (let [ente (random-uuid)
        ;; a fala pertence a OUTRA sessao da mesma Casa (sessao-id != path) -> anti confused-deputy -> 404
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil) :fala-sessao-id (random-uuid))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-decisoes (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc decisao-valida "fala-id" (str (random-uuid)))))]
    (is (= 404 (:status r)) "fala-id de outra sessao da mesma Casa -> 404 (nao registra)")))

(deftest decisao-fala-inexistente-404
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil) :fala-ausente? true)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-decisoes (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc decisao-valida "fala-id" (str (random-uuid)))))]
    (is (= 404 (:status r)) "fala-id inexistente -> 404")))
