(ns oplenario.sessoes.assiduidade-rotas-http-in-test
  "INTEGRACAO HTTP (DB-free) — Etapa 6 fatia 3: a rota `GET /assiduidade` (papel 'secretario') + a PROVA de
  que acrescenta-la NAO quebrou o roteamento das rotas irmas `/sessoes/:id/...` (obrigatorio no brief).

  RepoSessoes/RepoCadastros/RepoIdentidade FAKE (mesmo padrao de `folha-rotas-http-in-test`/
  `presenca-chamada-http-in-test`), exercitando `rotas/montar` (o HOST) DE VERDADE — e' onde a rota
  `/assiduidade` foi acrescentada NO TOPO (fora de `/sessoes/...`), e onde `roster-da-casa-em-datas` (seam
  da Etapa 6 fatia 1) e' construido e injetado.

  A REGRESSAO DE ROTEAMENTO empirica: uma repro ISOLADA fora deste ns (`io.pedestal.test/response-for` contra
  um service minimo com so' `/sessoes/:id` e `/sessoes/assiduidade`) mostrou que o `:id` de `/sessoes/:id`
  SOMBREIA o literal irmao — `GET /sessoes/assiduidade` roteava para `buscar-handler` (id=\"assiduidade\") e
  NUNCA chegava ao handler novo. E' a MESMA limitacao do router prefix-tree do Pedestal 0.7 ja' documentada
  em `oplenario.participacao.diplomat.http.in`/`oplenario.sessoes.diplomat.http.in` (`/gravacoes`) — por
  isso a rota final e' `/assiduidade`, no TOPO, como `/gravacoes`. Este ns prova as DUAS metades: a rota
  nova responde, E as rotas de 3+ segmentos (`/chamada`, `/quorum`, `/folha`) continuam intactas."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.cadastros.components.repositorio :as repo-cadastros-comp]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.components.objeto-store :as os]
            [oplenario.rotas :as rotas]
            [oplenario.sessoes.components.repositorio :as repo-sessoes])
  (:import (java.time Instant LocalDate)))

(def ^:private agendada-para (Instant/parse "2026-06-30T13:00:00Z"))
(def ^:private aberta-em (Instant/parse "2026-06-30T13:00:00Z"))
(def ^:private piso (Instant/parse "2026-06-30T00:00:00Z"))
(def ^:private data-s1 (LocalDate/of 2026 1 5))
(def ^:private data-s2 (LocalDate/of 2026 1 12))

;; ---------- fakes ----------

(defn- sessao-aberta [ente-id id]
  {:id id :ente-id ente-id :estado "aberta" :tipo-sessao "ordinaria" :transmite-publica true
   :agendada-para agendada-para :aberta-em aberta-em :encerrada-em nil
   :sessao-legislativa-id (random-uuid) :numero-sequencial 1 :modalidade "presencial"
   :delibera true :gera-ata-regimental true :permite-voto-secreto false :permite-modalidade-remota false
   :lock-version 0})

(defn- lido-aberta [ente-id id]
  {:sessao (sessao-aberta ente-id id) :instante aberta-em
   :presencas [] :justificativas [] :chamadas-conduzidas [] :piso piso :serie []})

(defn- assiduidade-vazia []
  {:sessoes [] :sessoes-sem-data-de-referencia 0 :presencas-por-sessao {} :justificativas-por-sessao {}})

(defn- fake-repo-sessoes
  "`sessao-fn`/`folha-fn` = `(ente-id id) -> ...` (irmaos de `chamada-da-sessao`/`folha-da-sessao`, mesmo
  contrato de `folha-rotas-http-in-test`). `assiduidade-fn` = `(ente-id periodo) -> leituras-assiduidade`,
  novo desta fatia."
  [{:keys [sessao-fn folha-fn assiduidade-fn] :or {assiduidade-fn (fn [_ _] (assiduidade-vazia))}}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ ente-id id] (sessao-fn ente-id id))
    (folha-da-sessao [_ ente-id id _agora] (folha-fn ente-id id))
    (chamada-da-sessao [_ ente-id id _agora] (folha-fn ente-id id))
    (leituras-assiduidade [_ ente-id periodo] (assiduidade-fn ente-id periodo))))

(defn- fake-repo-cadastros
  "`roster-lote-fn` = `(ente-id datas) -> {data -> [roster-linha]}` (Etapa 6 fatia 1). `roster-fn` = singular,
  usado pela chamada/quorum/folha (irmaos das rotas ja' existentes)."
  [{:keys [roster-fn roster-lote-fn] :or {roster-fn (fn [_ _] []) roster-lote-fn (fn [_ _] {})}}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cadastros-comp/RepoCadastros
    (roster-da-casa [_ ente-id data] (roster-fn ente-id data))
    (roster-da-casa-em-datas [_ ente-id datas] (roster-lote-fn ente-id datas))
    (buscar-ente [_ _ente-id] {:nome-oficial "Camara Municipal de Fortaleza" :nome-curto "CMF"})
    (legislatura-vigente [_ _ente-id] {:numero 19 :ano-inicio 2025 :ano-fim 2028})))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- fake-objeto-store []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify os/ObjetoStore
    (guardar! [_ chave _b _content-type] chave)
    (obter [_ _chave] nil)))

(defn- service-fn*
  [papeis repo-s repo-c]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-sessoes repo-s
                                   :repo-cadastros repo-c
                                   :objeto-store (fake-objeto-store)})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id] (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))
(defn- com-auth [tok] {"authorization" (str "Bearer " tok)})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(defn- url [& {:keys [de ate tipos formato recorte]
               :or {de "2026-01-01" ate "2026-01-31"}}]
  (str "/assiduidade?de=" de "&ate=" ate
       (when tipos (str "&tipos=" tipos))
       (when formato (str "&formato=" formato))
       (when recorte (str "&recorte=" recorte))))

;; ---------- fixture de uma apuracao real ----------

(def ^:private v-ana (random-uuid))

(defn- assiduidade-fixture [ente-id]
  (let [s1 (random-uuid) s2 (random-uuid)]
    (fn [eid _periodo]
      (if (= eid ente-id)
        {:sessoes [{:id s1 :numero 1 :tipo "ordinaria" :estado "encerrada"
                    :data-de-referencia data-s1 :transmite-publica true}
                   {:id s2 :numero 2 :tipo "secreta" :estado "encerrada"
                    :data-de-referencia data-s2 :transmite-publica false}]
         :sessoes-sem-data-de-referencia 0
         :presencas-por-sessao {s1 [{:vereador-id v-ana :tipo "entrada" :modalidade "plenario"}]
                                 s2 []}
         :justificativas-por-sessao {s1 [] s2 []}}
        (assiduidade-vazia)))))

(defn- roster-lote-fixture [ente-id]
  (fn [eid _datas]
    (if (= eid ente-id)
      {data-s1 [{:vereador-id v-ana :nome "Ana Pereira" :nome-parlamentar nil :partido "PDT"
                 :estado-mandato "vigente" :cargo-mesa nil}]
       data-s2 [{:vereador-id v-ana :nome "Ana Pereira" :nome-parlamentar nil :partido "PDT"
                 :estado-mandato "vigente" :cargo-mesa nil}]}
      {})))

;; ---------- caminho feliz — JSON ----------

(deftest feliz-json-com-secretario
  (let [ente (random-uuid) ident (random-uuid)
        repo-s (fake-repo-sessoes {:assiduidade-fn (assiduidade-fixture ente)})
        repo-c (fake-repo-cadastros {:roster-lote-fn (roster-lote-fixture ente)})
        svc (service-fn* #{"secretario"} repo-s repo-c)
        r (pt/response-for svc :get (url) :headers (com-auth (token ente ident)))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= "application/json; charset=utf-8" (get-in r [:headers "Content-Type"])))
    (is (= 2 (count (:sessoes body))))
    (is (= 1 (count (:vereadores body))))
    (is (= "Ana Pereira" (:nome (first (:vereadores body)))))
    (is (true? (:sigilosa (second (:sessoes body)))) "a sessao secreta sai marcada")
    (is (= 1 (get-in body [:totais :sessoes-sigilosas])) "totais chega inteiro no corpo")))

;; ---------- 401 / 403 ----------

(deftest sem-token-401
  (let [repo-s (fake-repo-sessoes {})
        repo-c (fake-repo-cadastros {})
        r (pt/response-for (service-fn* #{"secretario"} repo-s repo-c) :get (url))]
    (is (= 401 (:status r)))))

(deftest papel-vereador-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes {})
        repo-c (fake-repo-cadastros {})
        svc (service-fn* #{"vereador"} repo-s repo-c)
        r (pt/response-for svc :get (url) :headers (com-auth (token ente (random-uuid))))]
    (is (= 403 (:status r)))))

;; ---------- 400: cada forma invalida de entrada ----------

(deftest data-malformada-400
  (let [ente (random-uuid)
        svc (service-fn* #{"secretario"} (fake-repo-sessoes {}) (fake-repo-cadastros {}))
        auth (com-auth (token ente (random-uuid)))]
    (is (= 400 (:status (pt/response-for svc :get (url :de "31/01/2026") :headers auth))))
    (is (= 400 (:status (pt/response-for svc :get "/assiduidade?ate=2026-01-31" :headers auth)))
        "de ausente")))

(deftest de-posterior-a-ate-400
  (let [ente (random-uuid)
        svc (service-fn* #{"secretario"} (fake-repo-sessoes {}) (fake-repo-cadastros {}))
        auth (com-auth (token ente (random-uuid)))]
    (is (= 400 (:status (pt/response-for svc :get (url :de "2026-02-01" :ate "2026-01-01") :headers auth))))))

(deftest tipo-desconhecido-400-nunca-200-em-branco
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes {:assiduidade-fn (assiduidade-fixture ente)})
        repo-c (fake-repo-cadastros {:roster-lote-fn (roster-lote-fixture ente)})
        svc (service-fn* #{"secretario"} repo-s repo-c)
        auth (com-auth (token ente (random-uuid)))]
    (is (= 400 (:status (pt/response-for svc :get (url :tipos "ordinaria,tipo-que-nao-existe") :headers auth)))
        "o achado que 3 revisores da fatia 2 pegaram: NUNCA 200 com a apuracao em branco")))

(deftest param-REPETIDO-e-400-nos-CINCO-nunca-500-opaco
  ;; MEDIDO na revisao adversarial: `route/parse-query-string` devolve VETOR quando o param se repete, e
  ;; `str/blank?` sobre vetor estourava ClassCastException -> 500 `{"erro":"erro interno"}` + stack no log.
  ;; `?tipos=ordinaria&tipos=secreta` e' a convencao PADRAO de multivalor (`URLSearchParams.append`) — a Onda
  ;; E vai montar exatamente esse link.
  (let [ente (random-uuid)
        svc (service-fn* #{"secretario"} (fake-repo-sessoes {}) (fake-repo-cadastros {}))
        auth (com-auth (token ente (random-uuid)))]
    (doseq [[campo u] [[:de      "/assiduidade?de=2026-01-01&de=2026-02-01&ate=2026-01-31"]
                       [:ate     "/assiduidade?de=2026-01-01&ate=2026-01-31&ate=2026-02-28"]
                       [:tipos   "/assiduidade?de=2026-01-01&ate=2026-01-31&tipos=ordinaria&tipos=secreta"]
                       [:formato "/assiduidade?de=2026-01-01&ate=2026-01-31&formato=json&formato=csv"]
                       [:recorte "/assiduidade?de=2026-01-01&ate=2026-01-31&recorte=resumo&recorte=detalhe"]]]
      (let [r (pt/response-for svc :get u :headers auth)]
        (is (= 400 (:status r)) (str campo " repetido -> 400, nunca 500 opaco"))))))

(deftest tipos-degenerado-400-nunca-200-com-o-recorte-ALARGADO
  ;; `?tipos=,` caia em lista vazia -> nil -> TODOS os tipos, sessao `secreta` inclusive, com HTTP 200: o
  ;; defeito da Fatia 2 com o sinal invertido (filtro malformado -> resultado MAIS AMPLO que o pedido).
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes {:assiduidade-fn (assiduidade-fixture ente)})
        repo-c (fake-repo-cadastros {:roster-lote-fn (roster-lote-fixture ente)})
        svc (service-fn* #{"secretario"} repo-s repo-c)
        auth (com-auth (token ente (random-uuid)))]
    (doseq [t ["," ",,," "%20,%20"]]
      (is (= 400 (:status (pt/response-for svc :get (url :tipos t) :headers auth)))
          (str "tipos=" t " -> 400")))))

(deftest formato-desconhecido-400
  (let [ente (random-uuid)
        svc (service-fn* #{"secretario"} (fake-repo-sessoes {}) (fake-repo-cadastros {}))
        auth (com-auth (token ente (random-uuid)))]
    (is (= 400 (:status (pt/response-for svc :get (url :formato "xlsx") :headers auth))))))

(deftest recorte-desconhecido-400
  (let [ente (random-uuid)
        svc (service-fn* #{"secretario"} (fake-repo-sessoes {}) (fake-repo-cadastros {}))
        auth (com-auth (token ente (random-uuid)))]
    (is (= 400 (:status (pt/response-for svc :get (url :recorte "resumido") :headers auth))))))

;; ---------- 422: o teto do periodo, numero medido no corpo (interceptor global, sem repetir no handler) ----------

(deftest periodo-acima-do-teto-422-com-medido-e-teto
  (let [ente (random-uuid)
        svc (service-fn* #{"secretario"} (fake-repo-sessoes {}) (fake-repo-cadastros {}))
        auth (com-auth (token ente (random-uuid)))
        r (pt/response-for svc :get (url :de "2025-01-01" :ate "2026-01-02") :headers auth)
        body (ler-json r)]
    (is (= 422 (:status r)))
    (is (= 367 (:medido body)))
    (is (= 366 (:teto body)))))

;; ---------- CSV ----------

(deftest csv-content-type-e-disposition
  (let [ente (random-uuid) ident (random-uuid)
        repo-s (fake-repo-sessoes {:assiduidade-fn (assiduidade-fixture ente)})
        repo-c (fake-repo-cadastros {:roster-lote-fn (roster-lote-fixture ente)})
        svc (service-fn* #{"secretario"} repo-s repo-c)
        r (pt/response-for svc :get (url :formato "csv" :recorte "detalhe") :headers (com-auth (token ente ident)))]
    (is (= 200 (:status r)))
    (is (= "text/csv; charset=utf-8" (get-in r [:headers "Content-Type"])))
    (is (re-find #"attachment; filename=\"assiduidade-2026-01-01-a-2026-01-31-detalhe\.csv\""
                 (get-in r [:headers "Content-Disposition"])))
    (is (= "sandbox allow-downloads; default-src 'none'"
           (get-in r [:headers "Content-Security-Policy"]))
        "CSP RESTRITIVO sobrepondo o default do Pedestal (que libera script-src 'unsafe-inline'/'unsafe-eval')
         — a mesma correcao que a revisao adversarial da Etapa 5 obrigou na folha")
    (is (str/includes? (str (:body r)) "sessao-sigilosa")
        "a coluna de sigilo esta' no corpo do CSV servido pela rota real")
    (is (str/includes? (str (:body r)) "Ana Pereira"))))

(deftest csv-resumo-default-quando-recorte-ausente
  (let [ente (random-uuid) ident (random-uuid)
        repo-s (fake-repo-sessoes {:assiduidade-fn (assiduidade-fixture ente)})
        repo-c (fake-repo-cadastros {:roster-lote-fn (roster-lote-fixture ente)})
        svc (service-fn* #{"secretario"} repo-s repo-c)
        r (pt/response-for svc :get (url :formato "csv") :headers (com-auth (token ente ident)))]
    (is (str/includes? (get-in r [:headers "Content-Disposition"]) "-resumo.csv"))
    (is (str/includes? (str (:body r)) "sessoes-computadas"))))

;; ---------- multi-tenant fail-closed ----------

(deftest ator-da-casa-A-nao-ve-sessao-da-casa-B
  (let [casa-a (random-uuid) casa-b (random-uuid) ident (random-uuid)
        repo-s (fake-repo-sessoes {:assiduidade-fn (assiduidade-fixture casa-a)})
        repo-c (fake-repo-cadastros {:roster-lote-fn (roster-lote-fixture casa-a)})
        svc (service-fn* #{"secretario"} repo-s repo-c)
        r-a (pt/response-for svc :get (url) :headers (com-auth (token casa-a ident)))
        r-b (pt/response-for svc :get (url) :headers (com-auth (token casa-b ident)))
        body-a (ler-json r-a) body-b (ler-json r-b)]
    (is (= 200 (:status r-a)) "premissa: casa A ve' a apuracao dela")
    (is (= 2 (count (:sessoes body-a))))
    (is (= 200 (:status r-b)) "casa B faz o mesmo pedido, sem sessao nenhuma")
    (is (= 0 (count (:sessoes body-b))) "ator da Casa B nao ve nenhuma sessao da Casa A")
    (is (= 0 (count (:vereadores body-b))))))

;; =====================================================================================================
;; REGRESSAO DE ROTEAMENTO — obrigatoria: as rotas irmas `/sessoes/:id/...` continuam intactas
;; =====================================================================================================

(deftest sessoes-id-chamada-quorum-e-folha-continuam-roteando-para-os-handlers-certos
  (let [ente (random-uuid) sid (random-uuid) ident (random-uuid)
        repo-s (fake-repo-sessoes {:sessao-fn (fn [_ id] (sessao-aberta ente id))
                                   :folha-fn (fn [_ id] (lido-aberta ente id))})
        repo-c (fake-repo-cadastros {})
        svc (service-fn* #{"secretario"} repo-s repo-c)
        auth (com-auth (token ente ident))]
    (let [r (pt/response-for svc :get (str "/sessoes/" sid) :headers auth)]
      (is (= 200 (:status r)) "GET /sessoes/:id (buscar-handler) intacta")
      (is (= (str sid) (:id (ler-json r)))))
    (let [r (pt/response-for svc :get (str "/sessoes/" sid "/chamada") :headers auth)
          body (ler-json r)]
      (is (= 200 (:status r)) "GET /sessoes/:id/chamada intacta")
      (is (contains? body :linhas) "shape da CHAMADA, distinto do shape de /assiduidade")
      (is (contains? body :quorum)))
    (let [r (pt/response-for svc :get (str "/sessoes/" sid "/quorum") :headers auth)
          body (ler-json r)]
      (is (= 200 (:status r)) "GET /sessoes/:id/quorum intacta")
      (is (not (contains? body :linhas)) "shape MAGRO do quorum — distinto da chamada"))
    (let [r (pt/response-for svc :post (str "/sessoes/" sid "/folha") :headers auth)]
      (is (= 409 (:status r)) "POST /sessoes/:id/folha intacta — alcancou o gate D6 (sessao ABERTA)")
      (is (str/includes? (:erro (ler-json r)) "FECHADA")
          "a mensagem e' a do gate da FOLHA, prova de que NAO caiu no handler de assiduidade nem no de busca"))))

(deftest rota-de-assiduidade-nunca-e-sombreada-por-sessoes-id
  ;; O contraste direto: um `sessao-fn` que, se `/assiduidade` fosse capturado por `/sessoes/:id`, faria
  ;; `id-param->uuid` estourar (UUID/fromString de "assiduidade" lanca) — 500/400 opaco, nunca o payload
  ;; de assiduidade. A rota responde CERTO porque nem chega a essa funcao.
  (let [ente (random-uuid) ident (random-uuid)
        repo-s (fake-repo-sessoes {:sessao-fn (fn [_ _] (throw (ex-info "nunca deveria ser chamado" {})))
                                   :assiduidade-fn (assiduidade-fixture ente)})
        repo-c (fake-repo-cadastros {:roster-lote-fn (roster-lote-fixture ente)})
        svc (service-fn* #{"secretario"} repo-s repo-c)
        r (pt/response-for svc :get (url) :headers (com-auth (token ente ident)))]
    (is (= 200 (:status r)))
    (is (contains? (ler-json r) :sessoes))))
