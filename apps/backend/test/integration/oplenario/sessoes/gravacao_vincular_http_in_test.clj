(ns oplenario.sessoes.gravacao-vincular-http-in-test
  "Slice F4 — eixo D (§22.6): a borda HTTP de VINCULACAO de gravacao (Opcao A pos-upload). Apos a ingestao
  agnostica (POST /gravacoes), o servidor vincula o segmento a uma sessao: POST /sessoes/:id/gravacao/:seg-id/
  vincular, corpo {lock-version}. Carrega a sessao (nil -> 404), roda pode-ver-sessao? (mesma Casa -> 403), e
  RE-deriva o sigilo: sessao SECRETA forca acesso-restrito=true no vinculo (o flag do cliente na ingestao Opcao A
  pode ter vindo false). O Repo vincula UMA-VEZ (CAS WHERE sessao_id IS NULL + lock_version) — conflito/ja-
  vinculado/lock-stale -> 409. DB-free: RepoSessoes FAKE + idp-dev real — espelha o gravacao-http-in-test (W3)."
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

(defn- sessao-canonica
  "Sessao como buscar-sessao devolve (kebab) — so o que a authz fina (pode-ver-sessao? = mesma Casa) le + o
  tipo-sessao (p/ a re-derivacao do sigilo no vinculo)."
  [ente-id id]
  {:id id :ente-id ente-id :estado "aberta" :tipo-sessao "ordinaria"})

(defn- fake-repo-sessoes
  "RepoSessoes fake (parcial): `buscar-sessao` resolve a sessao (p/ a authz + o tipo); `vincular-segmento!` ECOA
  {:id :sessao-id} e GRAVA o mapa recebido em `capturado` — p/ provar id/sessao-id/lock-version/updated-by/
  forcar-acesso-restrito passados ao Repo. Se `conflito?`, `vincular-segmento!` LANCA o ex-info de CAS (409)."
  [busca-fn capturado & {:keys [conflito?]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ ente-id id] (busca-fn ente-id id))
    (vincular-segmento! [_ ente-id m]
      (when conflito?
        (throw (ex-info "vincular-segmento!: ja vinculada, conflito de lock_version ou inexistente"
                        {:tipo :conflito/vinculo :id (:id m)})))
      (reset! capturado (assoc m :ente-id ente-id))
      {:id (:id m) :sessao-id (:sessao-id m)})))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- service-fn*
  [papeis repo-s]
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
(defn- url [sid seg] (str "/sessoes/" sid "/gravacao/" seg "/vincular"))
(defn- corpo-lv [lv] (json/write-value-as-string {"lock-version" lv}))

;; ---------- POST /sessoes/:id/gravacao/:seg-id/vincular ----------

(deftest vincular-201
  (let [ente (random-uuid) sid (random-uuid) seg (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) cap)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url sid seg)
                           :headers (com-json (token ente (random-uuid))) :body (corpo-lv 0))
        body (ler-json r)]
    (is (= 200 (:status r)) "papel + sessao da mesma Casa + lock-version valido -> 200 (update, nao create)")
    (is (= (str seg) (:id body)) "recibo carrega o id do segmento vinculado")
    (is (= (str sid) (:sessao-id body)) "recibo carrega o id da sessao")
    (is (= seg (:id @cap)) "o Repo recebeu o segmento-id (uuid coagido na borda)")
    (is (= sid (:sessao-id @cap)) "o Repo recebeu a sessao-id (uuid coagido na borda)")
    (is (= 0 (:lock-version @cap)) "o Repo recebeu o lock-version do corpo (CAS otimista)")
    (is (false? (:forcar-acesso-restrito @cap)) "sessao ordinaria -> NAO forca acesso-restrito")))

(deftest vincular-sessao-secreta-forca-acesso-restrito
  ;; sigilo §22.6: re-vincular um segmento a uma sessao SECRETA forca acesso-restrito=true (o RE-vinculo recalcula
  ;; o flag que na ingestao Opcao A veio do cliente). Mesmo guard do slice de ingestao.
  (let [ente (random-uuid) sid (random-uuid) seg (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes (fn [_ id] (assoc (sessao-canonica ente id) :tipo-sessao "secreta")) cap)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url sid seg)
                           :headers (com-json (token ente (random-uuid))) :body (corpo-lv 2))]
    (is (= 200 (:status r)) "vinculo a sessao secreta -> 200")
    (is (true? (:forcar-acesso-restrito @cap))
        "sessao secreta -> servidor FORCA acesso-restrito=true no vinculo (sigilo)")))

(deftest vincular-depois-da-sessao-200
  ;; Faixa A / A.2 (§22.3.4 fonte PRIMARIA = gravacao local POS-sessao): o arquivo do OBS sobe depois que a
  ;; sessao encerrou, e o vinculo tem de passar — a gravacao e' o REGISTRO da sessao fechada, nao uma escrita de
  ;; conducao (o gate `exigir-sessao-aberta!` continua valendo p/ pauta/tribuna/mesa/incidente). 'arquivada'
  ;; tambem: e' o caminho da importacao de audio historico (fonte `importacao_legado`).
  (doseq [estado ["encerrada" "arquivada"]]
    (let [ente (random-uuid) sid (random-uuid) seg (random-uuid)
          cap (atom nil)
          repo-s (fake-repo-sessoes (fn [_ id] (assoc (sessao-canonica ente id) :estado estado)) cap)
          r (pt/response-for (service-fn* #{"secretario"} repo-s)
                             :post (url sid seg)
                             :headers (com-json (token ente (random-uuid))) :body (corpo-lv 0))]
      (is (= 200 (:status r)) (str "sessao " estado " aceita o vinculo da gravacao"))
      (is (= sid (:sessao-id @cap)) (str "o Repo vinculou o segmento a sessao " estado)))))

(deftest vincular-sessao-nao-realizada-409
  ;; a sessao que nao aconteceu nao tem gravacao: vincular um arquivo a ela e' erro de operacao, nao registro.
  (let [ente (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes (fn [_ id] (assoc (sessao-canonica ente id) :estado "nao_realizada")) cap)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid))) :body (corpo-lv 0))]
    (is (= 409 (:status r)) "sessao nao realizada -> 409")
    (is (re-find #"n[aã]o realizada" (:erro (ler-json r))) "a mensagem diz por que")
    (is (nil? @cap) "nada foi vinculado")))

(deftest vincular-papel-captacao-403
  ;; o papel `captacao` (a credencial do PC do OBS) SO' envia arquivos; vincular e' decisao da secretaria.
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"captacao"} repo-s)
                           :post (url (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid))) :body (corpo-lv 0))]
    (is (= 403 (:status r)) "captacao nao vincula")))

(deftest vincular-sessao-inexistente-404
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid))) :body (corpo-lv 0))]
    (is (= 404 (:status r)) "sessao inexistente no tenant -> 404")))

(deftest vincular-casa-alheia-403
  ;; buscar-sessao devolve sessao de OUTRA Casa (escapou da RLS por bug): pode-ver-sessao? (mesma Casa) NEGA.
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica (random-uuid) id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid))) :body (corpo-lv 0))]
    (is (= 403 (:status r)) "sessao de ente alheio -> policy.check (pode-ver-sessao?) nega -> 403")))

(deftest vincular-conflito-409
  ;; segmento ja vinculado / lock-version desatualizado: o Repo lanca o ex-info de CAS -> 409 (nao 500).
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil) :conflito? true)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid))) :body (corpo-lv 0))]
    (is (= 409 (:status r)) "ja vinculado / lock-version stale -> 409 conflito (vinculo uma-vez)")))

(deftest vincular-sem-papel-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"vereador"} repo-s)
                           :post (url (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid))) :body (corpo-lv 0))]
    (is (= 403 (:status r)) "ator sem papel 'secretario' -> authz grossa nega -> 403")))

(deftest vincular-sem-token-401
  (let [repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid) (random-uuid))
                           :headers {"Content-Type" "application/json"} :body (corpo-lv 0))]
    (is (= 401 (:status r)) "rota herda a cadeia de auth: sem token -> 401 (fail-closed)")))

(deftest vincular-lock-version-ausente-400
  ;; corpo sem lock-version -> a borda (adapters/in) barra com 400, nunca 500 do CAS.
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (json/write-value-as-string {"outra" 1}))]
    (is (= 400 (:status r)) "corpo sem lock-version -> 400 (validacao na borda)")))

(deftest vincular-lock-version-nao-inteiro-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid))) :body (corpo-lv "nao-e-int"))]
    (is (= 400 (:status r)) "lock-version nao-inteiro -> 400 (fail-closed na borda)")))

(deftest vincular-seg-id-malformado-400
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (str "/sessoes/" sid "/gravacao/nao-e-uuid/vincular")
                           :headers (com-json (token ente (random-uuid))) :body (corpo-lv 0))]
    (is (= 400 (:status r)) "seg-id malformado no path -> 400, nunca 500")))

(deftest vincular-sessao-id-malformado-400
  ;; cobertura simetrica ao seg-id: o :id (sessao) malformado no path tambem -> 400 na borda.
  (let [ente (random-uuid) seg (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (str "/sessoes/nao-e-uuid/gravacao/" seg "/vincular")
                           :headers (com-json (token ente (random-uuid))) :body (corpo-lv 0))]
    (is (= 400 (:status r)) "sessao :id malformado no path -> 400, nunca 500")))

(deftest vincular-lock-version-acima-do-teto-400
  ;; lock_version e' int4; um Long acima de Integer/MAX_VALUE passaria (integer?) mas estouraria no CAS -> 500.
  ;; A borda barra com 400 (fail-closed), nunca deixa virar erro interno.
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid) (random-uuid))
                           :headers (com-json (token ente (random-uuid))) :body (corpo-lv 3000000000))]
    (is (= 400 (:status r)) "lock-version acima de int4 (3e9) -> 400 (fail-closed), nunca 500 do CAS")))
