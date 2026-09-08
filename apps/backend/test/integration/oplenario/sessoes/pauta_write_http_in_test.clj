(ns oplenario.sessoes.pauta-write-http-in-test
  "Slice F4 — eixo B (§22.6), pauta VIVA: a BORDA HTTP de edicao da pauta. Tres rotas de escrita sobre a pauta
  1:1 da sessao: `POST /sessoes/:id/pauta/itens` (adicionar -> 201 {:id :ordem}, get-or-create do container
  transparente), `PATCH /sessoes/:id/pauta/itens/:item-id` (reordenar -> 200 {:id :de :para}) e
  `DELETE /sessoes/:id/pauta/itens/:item-id` (remover SOFT -> 200 {:id}; nunca DELETE fisico, Inv.10). A borda
  valida fail-closed -> 400 (fase/tipo-item, FK-por-tipo proposicao XOR texto, range int4 de ordem/lock); o
  reorder/remove mapeiam CAS-stale/item-removido -> 409 e item-de-outra-sessao -> 404 (anti confused-deputy,
  espelha cronometro/encerrar da tribuna). Carrega a sessao (nil->404), pode-ver-sessao? (mesma Casa->403).
  DB-free: RepoSessoes FAKE + idp-dev real — espelha o tribuna-decisao-http-in-test."
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
            [oplenario.sessoes.components.repositorio :as repo-sessoes]))

(defn- sessao-canonica [ente-id id]
  {:id id :ente-id ente-id :estado "aberta" :tipo-sessao "ordinaria"})

(defn- fake-repo-sessoes
  "RepoSessoes fake (parcial). `sessao-fn` resolve a sessao (default = mesma Casa). `pauta` = o que
  `buscar-pauta-por-sessao` devolve (nil = sessao sem pauta criada). `item` = o que `buscar-item` devolve
  (nil = item inexistente); o controller compara item.pauta-sessao-id com pauta.id (anti confused-deputy).
  As tres acoes de escrita GRAVAM o mapa em `cap` e ECOAM o recibo (ou aplicam `reordenar-fn`/`remover-fn`
  p/ simular conflito de CAS)."
  [& {:keys [sessao-fn pauta item reordenar-fn remover-fn cap]
      :or {sessao-fn sessao-canonica, cap (atom nil)}}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ ente-id id] (sessao-fn ente-id id))
    (buscar-pauta-por-sessao [_ _ente-id _sessao-id] pauta)
    (buscar-item [_ _ente-id id] (when item (assoc item :id id)))
    (adicionar-item-na-sessao! [_ ente-id m]
      (reset! cap (assoc m :ente-id ente-id))
      {:id (:id m) :ordem 1})
    (reordenar-item! [_ ente-id m]
      (reset! cap (assoc m :ente-id ente-id))
      ((or reordenar-fn (fn [mm] {:id (:id mm) :de 1 :para (:nova-ordem mm)})) m))
    (remover-item! [_ ente-id m]
      (reset! cap (assoc m :ente-id ente-id))
      ((or remover-fn (fn [mm] {:id (:id mm) :ativo false})) m))))

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
(defn- url-itens [sid] (str "/sessoes/" sid "/pauta/itens"))
(defn- url-item [sid iid] (str "/sessoes/" sid "/pauta/itens/" iid))

(def ^:private item-proposicao
  {"fase" "ordem_do_dia" "tipo-item" "proposicao" "proposicao-id" (str (random-uuid))})
(def ^:private item-leitura
  {"fase" "expediente" "tipo-item" "leitura" "texto-descricao" "Leitura do oficio n. 12/2026"})

;; ============================================================
;; POST /sessoes/:id/pauta/itens  (adicionar item)
;; ============================================================

(deftest adicionar-proposicao-201
  (let [ente (random-uuid) sid (random-uuid) op (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes :cap cap)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-itens sid)
                           :headers (com-json (token ente op))
                           :body (corpo item-proposicao))
        body (ler-json r)]
    (is (= 201 (:status r)) "papel + mesma Casa + corpo valido -> 201")
    (is (= (str (:id @cap)) (:id body)) "recibo carrega o id do item gravado")
    (is (= 1 (:ordem body)) "recibo carrega a ordem numerada server-side")
    (is (= sid (:sessao-id @cap)) "Repo recebeu a sessao-id (get-or-create do container)")
    (is (= "ordem_do_dia" (:fase @cap)) "Repo recebeu a fase")
    (is (= "proposicao" (:tipo-item @cap)) "Repo recebeu o tipo-item")
    (is (some? (:proposicao-id @cap)) "Repo recebeu a proposicao-id coagida")
    (is (nil? (:texto-descricao @cap)) "proposicao nao carrega texto-descricao")
    (is (= op (:created-by @cap)) "created-by INJETADO do ator")
    (is (some? (:id @cap)) "id do item gerado server-side (PK NOT NULL)")))

(deftest adicionar-leitura-201
  (let [ente (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes :cap cap)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-itens (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo item-leitura))]
    (is (= 201 (:status r)) "tipo nao-proposicao com texto-descricao -> 201")
    (is (= "Leitura do oficio n. 12/2026" (:texto-descricao @cap)) "Repo recebeu o texto-descricao")
    (is (nil? (:proposicao-id @cap)) "leitura nao carrega proposicao-id")))

(deftest adicionar-sessao-inexistente-404
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes :sessao-fn (fn [_ _] nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-itens (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo item-leitura))]
    (is (= 404 (:status r)) "sessao inexistente -> 404")))

(deftest adicionar-casa-alheia-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes :sessao-fn (fn [_ id] (sessao-canonica (random-uuid) id)))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-itens (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo item-leitura))]
    (is (= 403 (:status r)) "sessao de ente alheio -> 403")))

(deftest adicionar-sessao-encerrada-409
  ;; T2 grupo A achado #4 (ledger de prontidao Fase 8): verificado AO VIVO pelo Daouda — adicionar item de
  ;; pauta numa sessao ja ENCERRADA devolvia 201 (`{"id":"9f6278ce…","ordem":10}`). RED confirmado: antes de
  ;; `exigir-sessao-aberta!` existir em `adicionar-item-pauta`, este teste falhava com
  ;; `Expected: 409 Actual: 201`.
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes :sessao-fn (fn [e id] (assoc (sessao-canonica e id) :estado "encerrada")))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-itens (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo item-leitura))]
    (is (= 409 (:status r)) "sessao ja encerrada -> 409 (ata fechada nao admite item novo)")))

(deftest adicionar-sem-papel-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes)
        r (pt/response-for (service-fn* #{"vereador"} repo-s)
                           :post (url-itens (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo item-leitura))]
    (is (= 403 (:status r)) "sem papel 'secretario' -> 403")))

(deftest adicionar-sem-token-401
  (let [repo-s (fake-repo-sessoes)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-itens (random-uuid))
                           :headers {"Content-Type" "application/json"}
                           :body (corpo item-leitura))]
    (is (= 401 (:status r)) "sem token -> 401")))

(deftest adicionar-fase-invalida-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-itens (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc item-leitura "fase" "almoco")))]
    (is (= 400 (:status r)) "fase fora do enum -> 400")))

(deftest adicionar-tipo-invalido-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-itens (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc item-leitura "tipo-item" "musica")))]
    (is (= 400 (:status r)) "tipo-item fora do enum -> 400")))

(deftest adicionar-proposicao-sem-id-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-itens (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"fase" "ordem_do_dia" "tipo-item" "proposicao"}))]
    (is (= 400 (:status r)) "tipo proposicao exige proposicao-id -> 400 na borda")))

(deftest adicionar-proposicao-com-texto-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-itens (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc item-proposicao "texto-descricao" "texto proibido")))]
    (is (= 400 (:status r)) "proposicao com texto-descricao viola a FK-por-tipo -> 400")))

(deftest adicionar-leitura-sem-texto-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-itens (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"fase" "expediente" "tipo-item" "leitura"}))]
    (is (= 400 (:status r)) "tipo nao-proposicao exige texto-descricao -> 400")))

(deftest adicionar-leitura-texto-branco-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-itens (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc item-leitura "texto-descricao" "   ")))]
    (is (= 400 (:status r)) "texto-descricao so espacos (trim) -> 400")))

(deftest adicionar-proposicao-id-malformado-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-itens (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc item-proposicao "proposicao-id" "nao-e-uuid")))]
    (is (= 400 (:status r)) "proposicao-id malformada -> 400, nunca 500")))

(deftest adicionar-texto-longo-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url-itens (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc item-leitura "texto-descricao" (apply str (repeat 2001 "a")))))]
    (is (= 400 (:status r)) "texto-descricao acima de 2000 chars -> 400 (anti storage-amplification)")))

;; ============================================================
;; PATCH /sessoes/:id/pauta/itens/:item-id  (reordenar)
;; ============================================================

(def ^:private reordenar-valido {"nova-ordem" 3 "lock-version" 0})

(deftest reordenar-200
  (let [ente (random-uuid) sid (random-uuid) iid (random-uuid) pid (random-uuid) op (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes :pauta {:id pid} :item {:pauta-sessao-id pid} :cap cap)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :patch (url-item sid iid)
                           :headers (com-json (token ente op))
                           :body (corpo reordenar-valido))
        body (ler-json r)]
    (is (= 200 (:status r)) "item da sessao + lock valido -> 200 (atualiza, nao cria)")
    (is (= (str iid) (:id body)) "recibo carrega o id do item")
    (is (= 3 (:para body)) "recibo carrega a ordem destino")
    (is (= iid (:id @cap)) "Repo recebeu o item-id (path)")
    (is (= 3 (:nova-ordem @cap)) "Repo recebeu a nova-ordem")
    (is (= 0 (:lock-version @cap)) "Repo recebeu o lock-version")
    (is (= op (:updated-by @cap)) "updated-by INJETADO do ator")))

(deftest reordenar-sessao-encerrada-409
  ;; T2 grupo A achado #4 (ledger Fase 8) — mesma familia, mesmo gate `exigir-sessao-aberta!`.
  (let [ente (random-uuid) pid (random-uuid) iid (random-uuid)
        repo-s (fake-repo-sessoes :sessao-fn (fn [e id] (assoc (sessao-canonica e id) :estado "encerrada"))
                                  :pauta {:id pid} :item {:pauta-sessao-id pid})
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :patch (url-item (random-uuid) iid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo reordenar-valido))]
    (is (= 409 (:status r)) "sessao ja encerrada -> 409, antes de checar o item")))

(deftest reordenar-item-de-outra-sessao-404
  (let [ente (random-uuid) pid (random-uuid)
        ;; o item pertence a OUTRA pauta (pauta-sessao-id != a pauta da sessao do path) -> confused-deputy
        repo-s (fake-repo-sessoes :pauta {:id pid} :item {:pauta-sessao-id (random-uuid)})
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :patch (url-item (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo reordenar-valido))]
    (is (= 404 (:status r)) "item de outra pauta da mesma Casa -> 404 (anti confused-deputy)")))

(deftest reordenar-item-inexistente-404
  (let [ente (random-uuid) pid (random-uuid)
        repo-s (fake-repo-sessoes :pauta {:id pid} :item nil)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :patch (url-item (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo reordenar-valido))]
    (is (= 404 (:status r)) "item inexistente -> 404")))

(deftest reordenar-pauta-inexistente-404
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes :pauta nil :item {:pauta-sessao-id (random-uuid)})
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :patch (url-item (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo reordenar-valido))]
    (is (= 404 (:status r)) "sessao sem pauta criada -> 404 (nada a reordenar)")))

(deftest reordenar-sessao-inexistente-404
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes :sessao-fn (fn [_ _] nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :patch (url-item (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo reordenar-valido))]
    (is (= 404 (:status r)) "sessao inexistente -> 404")))

(deftest reordenar-casa-alheia-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes :sessao-fn (fn [_ id] (sessao-canonica (random-uuid) id)))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :patch (url-item (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo reordenar-valido))]
    (is (= 403 (:status r)) "sessao de ente alheio -> 403")))

(deftest reordenar-lock-stale-409
  (let [ente (random-uuid) pid (random-uuid)
        repo-s (fake-repo-sessoes
                :pauta {:id pid} :item {:pauta-sessao-id pid}
                :reordenar-fn (fn [_] (throw (ex-info "conflito de lock_version" {:tipo :conflito/pauta}))))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :patch (url-item (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo reordenar-valido))]
    (is (= 409 (:status r)) "lock-version desatualizado / item removido -> 409 (nao 500)")))

(deftest reordenar-sem-papel-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes)
        r (pt/response-for (service-fn* #{"vereador"} repo-s)
                           :patch (url-item (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo reordenar-valido))]
    (is (= 403 (:status r)) "sem papel 'secretario' -> 403")))

(deftest reordenar-nova-ordem-ausente-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :patch (url-item (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"lock-version" 0}))]
    (is (= 400 (:status r)) "corpo sem nova-ordem -> 400")))

(deftest reordenar-lock-version-ausente-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :patch (url-item (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"nova-ordem" 2}))]
    (is (= 400 (:status r)) "corpo sem lock-version -> 400")))

(deftest reordenar-lock-version-negativo-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :patch (url-item (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"nova-ordem" 2 "lock-version" -1}))]
    (is (= 400 (:status r)) "lock-version fora do range int4 (0..MAX) -> 400, nunca 500")))

(deftest reordenar-item-id-malformado-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :patch (url-item (random-uuid) "nao-e-uuid")
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo reordenar-valido))]
    (is (= 400 (:status r)) "item-id malformado -> 400, nunca 500")))

;; ============================================================
;; DELETE /sessoes/:id/pauta/itens/:item-id  (remover soft)
;; ============================================================

(def ^:private remover-valido {"tipo" "exclusao" "lock-version" 0})

(deftest remover-200
  (let [ente (random-uuid) sid (random-uuid) iid (random-uuid) pid (random-uuid) op (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes :pauta {:id pid} :item {:pauta-sessao-id pid} :cap cap)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :delete (url-item sid iid)
                           :headers (com-json (token ente op))
                           :body (corpo remover-valido))
        body (ler-json r)]
    (is (= 200 (:status r)) "item da sessao + tipo valido + lock -> 200 (soft-remove, atualiza)")
    (is (= (str iid) (:id body)) "recibo carrega o id do item removido")
    (is (= iid (:id @cap)) "Repo recebeu o item-id (path)")
    (is (= "exclusao" (:tipo @cap)) "Repo recebeu o tipo de remocao")
    (is (= 0 (:lock-version @cap)) "Repo recebeu o lock-version")
    (is (= op (:updated-by @cap)) "updated-by INJETADO do ator")
    (is (nil? (:justificativa @cap)) "sem justificativa no corpo -> nil")))

(deftest remover-sessao-encerrada-409
  ;; T2 grupo A achado #4 (ledger Fase 8) — mesma familia, mesmo gate `exigir-sessao-aberta!`.
  (let [ente (random-uuid) pid (random-uuid) iid (random-uuid)
        repo-s (fake-repo-sessoes :sessao-fn (fn [e id] (assoc (sessao-canonica e id) :estado "encerrada"))
                                  :pauta {:id pid} :item {:pauta-sessao-id pid})
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :delete (url-item (random-uuid) iid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo remover-valido))]
    (is (= 409 (:status r)) "sessao ja encerrada -> 409, antes de checar o item")))

(deftest remover-com-justificativa-200
  (let [ente (random-uuid) pid (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes :pauta {:id pid} :item {:pauta-sessao-id pid} :cap cap)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :delete (url-item (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc remover-valido "tipo" "retirada_pedido_autor"
                                               "justificativa" "Autor pediu a retirada em plenario")))]
    (is (= 200 (:status r)) "retirada com justificativa -> 200")
    (is (= "retirada_pedido_autor" (:tipo @cap)) "Repo recebeu o tipo")
    (is (= "Autor pediu a retirada em plenario" (:justificativa @cap)) "Repo recebeu a justificativa")))

(deftest remover-tipo-invalido-400
  (let [ente (random-uuid) pid (random-uuid)
        repo-s (fake-repo-sessoes :pauta {:id pid} :item {:pauta-sessao-id pid})
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :delete (url-item (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc remover-valido "tipo" "inclusao")))]
    (is (= 400 (:status r)) "tipo fora de {exclusao,retirada_pedido_autor} -> 400 na borda")))

(deftest remover-justificativa-branco-400
  (let [ente (random-uuid) pid (random-uuid)
        repo-s (fake-repo-sessoes :pauta {:id pid} :item {:pauta-sessao-id pid})
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :delete (url-item (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc remover-valido "justificativa" "   ")))]
    (is (= 400 (:status r)) "justificativa presente mas em branco -> 400")))

(deftest remover-item-de-outra-sessao-404
  (let [ente (random-uuid) pid (random-uuid)
        repo-s (fake-repo-sessoes :pauta {:id pid} :item {:pauta-sessao-id (random-uuid)})
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :delete (url-item (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo remover-valido))]
    (is (= 404 (:status r)) "item de outra pauta -> 404 (anti confused-deputy)")))

(deftest remover-lock-stale-409
  (let [ente (random-uuid) pid (random-uuid)
        repo-s (fake-repo-sessoes
                :pauta {:id pid} :item {:pauta-sessao-id pid}
                :remover-fn (fn [_] (throw (ex-info "item ja removido" {:tipo :conflito/pauta}))))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :delete (url-item (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo remover-valido))]
    (is (= 409 (:status r)) "item ja removido / lock-stale -> 409 (nao 500)")))

(deftest remover-lock-version-ausente-400
  (let [ente (random-uuid) pid (random-uuid)
        repo-s (fake-repo-sessoes :pauta {:id pid} :item {:pauta-sessao-id pid})
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :delete (url-item (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"tipo" "exclusao"}))]
    (is (= 400 (:status r)) "corpo sem lock-version -> 400")))

(deftest remover-justificativa-longa-400
  (let [ente (random-uuid) pid (random-uuid)
        repo-s (fake-repo-sessoes :pauta {:id pid} :item {:pauta-sessao-id pid})
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :delete (url-item (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo (assoc remover-valido "justificativa" (apply str (repeat 2001 "a")))))]
    (is (= 400 (:status r)) "justificativa acima de 2000 chars -> 400")))

(deftest remover-sem-papel-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes)
        r (pt/response-for (service-fn* #{"vereador"} repo-s)
                           :delete (url-item (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo remover-valido))]
    (is (= 403 (:status r)) "sem papel 'secretario' -> 403")))
