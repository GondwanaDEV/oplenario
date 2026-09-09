(ns oplenario.sessoes.tribuna-fala-http-in-test
  "Slice F4 — eixo F (§22.6), tribuna camada de EXECUCAO (F4.5b): a borda HTTP da fala + cronometro.
  `POST /sessoes/:id/falas` inicia (corpo {orador-id, tipo-fala, fase, iniciou-em, inscricao-id?, fala-pai-id?,
  proposicao-ref-id?}) -> 201 {:fala-id}; `POST /sessoes/:id/falas/:fala-id/cronometro` registra evento manual
  (corpo {tipo, ocorrido-em, segundos-adicionais?}) -> 201 {:id}; `POST /sessoes/:id/falas/:fala-id/encerrar`
  computa o tempo + crava encerrou-em (corpo {encerrou-em, lock-version}) -> 200 {:fala-id :tempo-segundos},
  conflito/CAS/ja-encerrada -> 409. O Repo compoe o ato + emite fala.iniciada/cronometro/encerrada (fila ao vivo)
  na MESMA tx — este slice so fecha a borda. Carrega a sessao (nil->404), pode-ver-sessao? (mesma Casa->403);
  tipo/fase fora do enum / uuid malformado / lock fora de int4 / instante nao-ISO / incoerencia do cronometro ->
  400. DB-free: RepoSessoes FAKE + idp-dev real — espelha o tribuna-inscricao-http-in-test."
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
  confused-deputy); `iniciar-fala!`/`registrar-evento-cronometro!` GRAVAM o mapa em `capturado` e ECOAM o recibo;
  `encerrar-fala!` ECOA {:id :tempo-efetivamente-usado-segundos} (ou LANCA :conflito/fala se `conflito?`)."
  [busca-fn capturado & {:keys [conflito? fala-sessao-id fala-ausente?]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ ente-id id] (busca-fn ente-id id))
    (buscar-fala [_ _ente-id id]
      (when-not fala-ausente? {:id id :sessao-id fala-sessao-id}))
    (iniciar-fala! [_ ente-id m]
      (reset! capturado (assoc m :ente-id ente-id))
      {:id (:id m)})
    (registrar-evento-cronometro! [_ ente-id m]
      (reset! capturado (assoc m :ente-id ente-id))
      {:id (random-uuid)})
    (encerrar-fala! [_ ente-id m]
      (when conflito?
        (throw (ex-info "encerrar-fala!: conflito" {:tipo :conflito/fala :id (:id m)})))
      (reset! capturado (assoc m :ente-id ente-id))
      {:id (:id m) :tempo-efetivamente-usado-segundos 42})))

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
(defn- url-falas [sid] (str "/sessoes/" sid "/falas"))
(defn- url-cronometro [sid fid] (str "/sessoes/" sid "/falas/" fid "/cronometro"))
(defn- url-encerrar [sid fid] (str "/sessoes/" sid "/falas/" fid "/encerrar"))

(def ^:private fala-valida
  {"tipo-fala" "principal" "fase" "expediente" "iniciou-em" "2026-06-30T12:00:00Z"})

;; ---------- POST /sessoes/:id/falas (iniciar fala) ----------

(deftest iniciar-201
  (let [ente (random-uuid) sid (random-uuid) oid (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) cap)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-falas sid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc fala-valida "orador-id" (str oid))))
        body (ler-json r)]
    (is (= 201 (:status r)) "papel + mesma Casa + corpo valido -> 201")
    (is (= (str (:id @cap)) (:fala-id body)) "recibo carrega o id da fala")
    (is (= sid (:sessao-id @cap)) "Repo recebeu a sessao-id (path)")
    (is (= oid (:orador-id @cap)) "Repo recebeu o orador-id (corpo)")
    (is (= "principal" (:tipo-fala @cap)) "Repo recebeu o tipo-fala")
    (is (= "expediente" (:fase @cap)) "Repo recebeu a fase")
    (is (= (Instant/parse "2026-06-30T12:00:00Z") (:iniciou-em @cap)) "Repo recebeu o iniciou-em coagido a Instant")
    (is (some? (:created-by @cap)) "Repo recebeu created-by (do ator)")))

(deftest iniciar-com-opcionais-201
  (let [ente (random-uuid) sid (random-uuid) iid (random-uuid) pai (random-uuid) pid (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) cap)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-falas sid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc fala-valida "orador-id" (str (random-uuid))
                                               "tipo-fala" "aparte"
                                               "inscricao-id" (str iid)
                                               "fala-pai-id" (str pai)
                                               "proposicao-ref-id" (str pid))))]
    (is (= 201 (:status r)) "opcionais aceitos -> 201")
    (is (= iid (:inscricao-id @cap)) "Repo recebeu o inscricao-id coagido")
    (is (= pai (:fala-pai-id @cap)) "Repo recebeu o fala-pai-id coagido")
    (is (= pid (:proposicao-ref-id @cap)) "Repo recebeu o proposicao-ref-id coagido")))

(deftest iniciar-sessao-encerrada-409
  ;; T2 grupo A achado #4 (ledger de prontidao Fase 8): mesmo gate `exigir-sessao-aberta!` da familia tribuna.
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (assoc (sessao-canonica ente id) :estado "encerrada")) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-falas (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc fala-valida "orador-id" (str (random-uuid)))))]
    (is (= 409 (:status r)) "sessao ja encerrada -> 409")))

(deftest iniciar-sessao-inexistente-404
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-falas (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc fala-valida "orador-id" (str (random-uuid)))))]
    (is (= 404 (:status r)) "sessao inexistente -> 404")))

(deftest iniciar-casa-alheia-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica (random-uuid) id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-falas (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc fala-valida "orador-id" (str (random-uuid)))))]
    (is (= 403 (:status r)) "sessao de ente alheio -> 403")))

(deftest iniciar-sem-papel-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"vereador"} repo-s)
                           :post (url-falas (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc fala-valida "orador-id" (str (random-uuid)))))]
    (is (= 403 (:status r)) "sem papel 'secretario' -> 403")))

(deftest iniciar-sem-token-401
  (let [repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-falas (random-uuid))
                           :headers {"Content-Type" "application/json"}
                           :body (corpo (assoc fala-valida "orador-id" (str (random-uuid)))))]
    (is (= 401 (:status r)) "sem token -> 401")))

(deftest iniciar-tipo-fala-invalido-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-falas (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc fala-valida "orador-id" (str (random-uuid))
                                               "tipo-fala" "monologo")))]
    (is (= 400 (:status r)) "tipo-fala fora do enum -> 400")))

(deftest iniciar-fase-invalida-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-falas (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc fala-valida "orador-id" (str (random-uuid))
                                               "fase" "intervalo")))]
    (is (= 400 (:status r)) "fase fora do enum -> 400")))

(deftest iniciar-orador-malformado-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-falas (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc fala-valida "orador-id" "nao-e-uuid")))]
    (is (= 400 (:status r)) "orador-id malformado -> 400")))

(deftest iniciar-iniciou-em-invalido-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-falas (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc fala-valida "orador-id" (str (random-uuid))
                                               "iniciou-em" "ontem")))]
    (is (= 400 (:status r)) "iniciou-em nao-ISO-8601 -> 400")))

(deftest iniciar-inscricao-id-malformada-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-falas (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc fala-valida "orador-id" (str (random-uuid))
                                               "inscricao-id" "xyz")))]
    (is (= 400 (:status r)) "inscricao-id presente mas malformada -> 400")))

(deftest iniciar-orador-ausente-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-falas (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           ;; corpo SEM orador-id (campo obrigatorio) -> Malli rejeita -> 400
                           :body (corpo fala-valida))]
    (is (= 400 (:status r)) "corpo sem orador-id (obrigatorio) -> 400")))

;; ---------- POST /sessoes/:id/falas/:fala-id/cronometro (evento manual) ----------

(deftest cronometro-201
  (let [ente (random-uuid) sid (random-uuid) fid (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) cap :fala-sessao-id sid)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-cronometro sid fid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"tipo" "pausada" "ocorrido-em" "2026-06-30T12:05:00Z"}))
        body (ler-json r)]
    (is (= 201 (:status r)) "tipo manual + corpo valido -> 201")
    (is (some? (:id body)) "recibo carrega o id do evento")
    (is (= fid (:fala-id @cap)) "Repo recebeu a fala-id (path)")
    (is (= "pausada" (:tipo @cap)) "Repo recebeu o tipo")
    (is (= (Instant/parse "2026-06-30T12:05:00Z") (:ocorrido-em @cap)) "Repo recebeu o ocorrido-em coagido")
    (is (nil? (:segundos-adicionais @cap)) "pausada nao carrega segundos-adicionais")
    (is (some? (:created-by @cap)) "Repo recebeu created-by (do ator)")))

(deftest cronometro-tempo-adicional-201
  (let [ente (random-uuid) sid (random-uuid) fid (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) cap :fala-sessao-id sid)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-cronometro sid fid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"tipo" "tempo_adicional_concedido" "ocorrido-em" "2026-06-30T12:06:00Z"
                                         "segundos-adicionais" 120}))]
    (is (= 201 (:status r)) "tempo_adicional_concedido com segundos > 0 -> 201")
    (is (= 120 (:segundos-adicionais @cap)) "Repo recebeu os segundos-adicionais")))

(deftest cronometro-sessao-encerrada-409
  ;; T2 grupo A achado #4 (ledger Fase 8) — mesmo gate, mesma familia.
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (assoc (sessao-canonica ente id) :estado "encerrada")) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-cronometro (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"tipo" "pausada" "ocorrido-em" "2026-06-30T12:05:00Z"}))]
    (is (= 409 (:status r)) "sessao ja encerrada -> 409")))

(deftest cronometro-sessao-inexistente-404
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-cronometro (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"tipo" "pausada" "ocorrido-em" "2026-06-30T12:05:00Z"}))]
    (is (= 404 (:status r)) "sessao inexistente -> 404")))

(deftest cronometro-casa-alheia-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica (random-uuid) id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-cronometro (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"tipo" "pausada" "ocorrido-em" "2026-06-30T12:05:00Z"}))]
    (is (= 403 (:status r)) "sessao de ente alheio -> 403")))

(deftest cronometro-sem-papel-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"vereador"} repo-s)
                           :post (url-cronometro (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"tipo" "pausada" "ocorrido-em" "2026-06-30T12:05:00Z"}))]
    (is (= 403 (:status r)) "sem papel 'secretario' -> 403")))

(deftest cronometro-tipo-invalido-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-cronometro (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           ;; "iniciada" e' do ciclo da fala, NAO um evento manual -> 400 na borda
                           :body (corpo {"tipo" "iniciada" "ocorrido-em" "2026-06-30T12:05:00Z"}))]
    (is (= 400 (:status r)) "tipo fora do enum manual -> 400")))

(deftest cronometro-tempo-adicional-sem-segundos-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-cronometro (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"tipo" "tempo_adicional_concedido" "ocorrido-em" "2026-06-30T12:05:00Z"}))]
    (is (= 400 (:status r)) "tempo_adicional sem segundos-adicionais > 0 -> 400 (coerencia na borda, nao 500)")))

(deftest cronometro-segundos-em-tipo-errado-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-cronometro (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"tipo" "pausada" "ocorrido-em" "2026-06-30T12:05:00Z"
                                         "segundos-adicionais" 30}))]
    (is (= 400 (:status r)) "segundos-adicionais em tipo != tempo_adicional -> 400 (coerencia na borda)")))

(deftest cronometro-fala-id-malformada-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (str "/sessoes/" (random-uuid) "/falas/nao-e-uuid/cronometro")
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"tipo" "pausada" "ocorrido-em" "2026-06-30T12:05:00Z"}))]
    (is (= 400 (:status r)) ":fala-id malformada no path -> 400, nunca 500")))

(deftest cronometro-ocorrido-em-invalido-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-cronometro (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"tipo" "pausada" "ocorrido-em" "agora"}))]
    (is (= 400 (:status r)) "ocorrido-em nao-ISO-8601 -> 400")))

(deftest cronometro-fala-de-outra-sessao-404
  (let [ente (random-uuid)
        ;; a fala pertence a OUTRA sessao da mesma Casa (sessao-id != path) -> anti confused-deputy -> 404
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil) :fala-sessao-id (random-uuid))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-cronometro (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"tipo" "pausada" "ocorrido-em" "2026-06-30T12:05:00Z"}))]
    (is (= 404 (:status r)) "fala de outra sessao da mesma Casa -> 404 (nao cronometra)")))

(deftest cronometro-fala-inexistente-404
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil) :fala-ausente? true)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-cronometro (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"tipo" "pausada" "ocorrido-em" "2026-06-30T12:05:00Z"}))]
    (is (= 404 (:status r)) "fala inexistente -> 404")))

(deftest cronometro-segundos-overflow-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-cronometro (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"tipo" "tempo_adicional_concedido" "ocorrido-em" "2026-06-30T12:05:00Z"
                                         "segundos-adicionais" (inc Integer/MAX_VALUE)}))]
    (is (= 400 (:status r)) "segundos-adicionais alem de int4 -> 400 na borda, nunca 500 do driver")))

;; ---------- POST /sessoes/:id/falas/:fala-id/encerrar ----------

(deftest encerrar-200
  (let [ente (random-uuid) sid (random-uuid) fid (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) cap :fala-sessao-id sid)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-encerrar sid fid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"encerrou-em" "2026-06-30T12:30:00Z" "lock-version" 0}))
        body (ler-json r)]
    (is (= 200 (:status r)) "encerramento valido -> 200 (atualiza, nao cria)")
    (is (= (str fid) (:fala-id body)) "recibo carrega a fala-id")
    (is (= 42 (:tempo-segundos body)) "recibo carrega o tempo computado")
    (is (= fid (:id @cap)) "Repo recebeu o id da fala (path)")
    (is (= (Instant/parse "2026-06-30T12:30:00Z") (:encerrou-em @cap)) "Repo recebeu o encerrou-em coagido")
    (is (= 0 (:lock-version @cap)) "Repo recebeu o lock-version (CAS)")
    (is (some? (:updated-by @cap)) "Repo recebeu updated-by (do ator)")))

(deftest encerrar-fala-sessao-encerrada-409
  ;; T2 grupo A achado #4 (ledger Fase 8) — mesmo gate, mesma familia. Ordem confirmada: o gate roda ANTES do
  ;; guard de conflito de lock/estado-terminal da fala (`encerrar-conflito-409` abaixo).
  (let [ente (random-uuid) sid (random-uuid) fid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (assoc (sessao-canonica ente id) :estado "encerrada")) (atom nil)
                                  :fala-sessao-id sid)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-encerrar sid fid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"encerrou-em" "2026-06-30T12:30:00Z" "lock-version" 0}))]
    (is (= 409 (:status r)) "sessao ja encerrada -> 409")))

(deftest encerrar-conflito-409
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil) :conflito? true :fala-sessao-id sid)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-encerrar sid (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"encerrou-em" "2026-06-30T12:30:00Z" "lock-version" 0}))]
    (is (= 409 (:status r)) "ja encerrada / lock-stale / inexistente -> 409 (nao 500)")))

(deftest encerrar-sessao-inexistente-404
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-encerrar (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"encerrou-em" "2026-06-30T12:30:00Z" "lock-version" 0}))]
    (is (= 404 (:status r)) "sessao inexistente -> 404")))

(deftest encerrar-casa-alheia-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica (random-uuid) id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-encerrar (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"encerrou-em" "2026-06-30T12:30:00Z" "lock-version" 0}))]
    (is (= 403 (:status r)) "sessao de ente alheio -> 403")))

(deftest encerrar-sem-papel-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"vereador"} repo-s)
                           :post (url-encerrar (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"encerrou-em" "2026-06-30T12:30:00Z" "lock-version" 0}))]
    (is (= 403 (:status r)) "sem papel 'secretario' -> 403")))

(deftest encerrar-lock-version-ausente-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-encerrar (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"encerrou-em" "2026-06-30T12:30:00Z"}))]
    (is (= 400 (:status r)) "corpo sem lock-version -> 400")))

(deftest encerrar-encerrou-em-ausente-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-encerrar (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"lock-version" 0}))]
    (is (= 400 (:status r)) "corpo sem encerrou-em -> 400")))

(deftest encerrar-fala-id-malformada-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (str "/sessoes/" (random-uuid) "/falas/nao-e-uuid/encerrar")
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"encerrou-em" "2026-06-30T12:30:00Z" "lock-version" 0}))]
    (is (= 400 (:status r)) ":fala-id malformada no path -> 400, nunca 500")))

(deftest encerrar-fala-de-outra-sessao-404
  (let [ente (random-uuid)
        ;; a fala pertence a OUTRA sessao da mesma Casa -> anti confused-deputy -> 404 (nao encerra)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil) :fala-sessao-id (random-uuid))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-encerrar (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"encerrou-em" "2026-06-30T12:30:00Z" "lock-version" 0}))]
    (is (= 404 (:status r)) "fala de outra sessao da mesma Casa -> 404")))

(deftest encerrar-fala-inexistente-404
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil) :fala-ausente? true)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-encerrar (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"encerrou-em" "2026-06-30T12:30:00Z" "lock-version" 0}))]
    (is (= 404 (:status r)) "fala inexistente -> 404")))
