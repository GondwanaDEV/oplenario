(ns oplenario.sessoes.folha-rotas-http-in-test
  "INTEGRACAO HTTP (DB-free) — Etapa 5 fatia 5: as QUATRO rotas da FOLHA DA SESSAO
  (`POST /sessoes/:id/folha`, `GET /sessoes/:id/folhas[/:versao[/pdf]]`).

  RepoSessoes/RepoCadastros/RepoIdentidade FAKE (mesmo padrao de `quorum-http-in-test`/
  `presenca-chamada-http-in-test`) + objeto_store FAKE em memoria — mas os DOIS ports de RENDERIZACAO sao
  REAIS (`serializador-folha-html`, `renderizador-pdf` via openhtmltopdf de verdade), porque o teste central
  da fatia (o GET devolve BYTE A BYTE o que foi congelado) so' vale alguma coisa contra o serializador real.

  Exercita `rotas/montar` (o HOST) DE VERDADE — e' onde `dados-da-casa-fn` (seam criado na Fatia 1, nunca
  fiado ate' agora) e os dois ports novos (`:serializador-folha`/`:renderizador-pdf`, decorados com teto e
  timeout) sao construidos e injetados no fragmento de `sessoes`."
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
  (:import (java.security MessageDigest)
           (java.time Instant)
           (org.postgresql.util PSQLException PSQLState)))

(def ^:private agendada-para (Instant/parse "2026-06-30T13:00:00Z"))
(def ^:private aberta-em (Instant/parse "2026-06-30T13:00:00Z"))
(def ^:private encerrada-em (Instant/parse "2026-06-30T18:00:00Z"))
(def ^:private piso (Instant/parse "2026-06-30T00:00:00Z"))

(def ^:private v-ana (random-uuid))
(def ^:private v-bruno (random-uuid))

(defn- roster-fn [_ente-id _data]
  [{:vereador-id v-ana :nome "Ana" :nome-parlamentar nil :partido "PDT"
    :estado-mandato "vigente" :cargo-mesa "presidente"}
   {:vereador-id v-bruno :nome "Bruno" :nome-parlamentar nil :partido "PT"
    :estado-mandato "vigente" :cargo-mesa nil}])

(defn- presencas []
  [{:vereador-id v-ana :tipo "entrada" :modalidade "plenario" :fonte "manual_secretaria"
    :ocorrido-em aberta-em :registrado-em aberta-em}
   {:vereador-id v-bruno :tipo "entrada" :modalidade "plenario" :fonte "manual_secretaria"
    :ocorrido-em aberta-em :registrado-em aberta-em}])

(defn- sessao-encerrada [ente-id id]
  {:id id :ente-id ente-id :estado "encerrada" :tipo-sessao "ordinaria" :transmite-publica true
   :agendada-para agendada-para :aberta-em aberta-em :encerrada-em encerrada-em})

(defn- sessao-secreta [ente-id id]
  {:id id :ente-id ente-id :estado "encerrada" :tipo-sessao "secreta" :transmite-publica false
   :agendada-para agendada-para :aberta-em aberta-em :encerrada-em encerrada-em})

(defn- sessao-aberta [ente-id id]
  {:id id :ente-id ente-id :estado "aberta" :tipo-sessao "ordinaria" :transmite-publica true
   :agendada-para agendada-para :aberta-em aberta-em :encerrada-em nil})

(defn- lido-fechada [ente-id id]
  {:sessao (sessao-encerrada ente-id id) :instante encerrada-em
   :presencas (presencas) :justificativas [] :chamadas-conduzidas []
   :piso piso :serie []})

(defn- lido-secreta [ente-id id]
  {:sessao (sessao-secreta ente-id id) :instante encerrada-em
   :presencas (presencas) :justificativas [] :chamadas-conduzidas []
   :piso piso :serie []})

(defn- lido-aberta [ente-id id]
  {:sessao (sessao-aberta ente-id id) :instante aberta-em
   :presencas [] :justificativas [] :chamadas-conduzidas [] :piso piso :serie []})

;; ---------- fakes ----------

(defn- fake-repo-sessoes
  "`sessao-fn`/`folha-fn` sao `(ente-id sessao-id) -> ...`, o mesmo par que `buscar-sessao`/
  `folha-da-sessao` expoem. `inserir-dedup-fn`, quando dado, SUBSTITUI a escrita normal (usado so' pelo
  teste da segunda colisao de 23505)."
  [{:keys [sessao-fn folha-fn inserir-dedup-fn]}]
  (let [folhas (atom {})]
    #_{:clj-kondo/ignore [:missing-protocol-method]}
    (reify repo-sessoes/RepoSessoes
      (buscar-sessao [_ ente-id id] (sessao-fn ente-id id))
      (folha-da-sessao [_ ente-id id _agora] (folha-fn ente-id id))
      (max-versao-da-folha [_ ente-id sessao-id]
        (reduce max 0 (keep #(when (and (= ente-id (:ente-id %)) (= sessao-id (:sessao-id %))) (:versao %))
                             (vals @folhas))))
      (inserir-folha-dedup! [_ ente-id row _desde]
        (if inserir-dedup-fn
          (inserir-dedup-fn ente-id row)
          (let [row* (assoc row :ente-id ente-id :criado-em (Instant/now))]
            (swap! folhas assoc (:id row*) row*)
            row*)))
      (buscar-folha [_ ente-id sessao-id versao]
        (first (filter #(and (= ente-id (:ente-id %)) (= sessao-id (:sessao-id %)) (= versao (:versao %)))
                       (vals @folhas))))
      (folhas-da-sessao [_ ente-id sessao-id]
        (->> (vals @folhas)
             (filter #(and (= ente-id (:ente-id %)) (= sessao-id (:sessao-id %))))
             (sort-by :versao >)))
      (folha-recente-do-ator [_ _ente-id _sessao-id _gerada-por _desde] nil))))

(defn- fake-repo-cadastros []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cadastros-comp/RepoCadastros
    (roster-da-casa [_ ente-id data] (roster-fn ente-id data))
    (buscar-ente [_ _ente-id] {:nome-oficial "Camara Municipal de Fortaleza" :nome-curto "CMF"})
    (legislatura-vigente [_ _ente-id] {:numero 19 :ano-inicio 2025 :ano-fim 2028})))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- fake-objeto-store []
  (let [dados (atom {})]
    #_{:clj-kondo/ignore [:missing-protocol-method]}
    (reify os/ObjetoStore
      (guardar! [_ chave b content-type] (swap! dados assoc chave {:bytes b :content-type content-type}) chave)
      (obter [_ chave] (:bytes (get @dados chave))))))

(defn- service-fn* [papeis repo-s]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-sessoes repo-s
                                   :repo-cadastros (fake-repo-cadastros)
                                   :objeto-store (fake-objeto-store)})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id] (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))
(defn- com-auth [tok] {"authorization" (str "Bearer " tok)})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))
(defn- sha256-hex [^bytes b]
  (str "sha256:" (apply str (map #(format "%02x" (bit-and % 0xff))
                                  (.digest (MessageDigest/getInstance "SHA-256") b)))))

(defn- url-gerar [sid] (str "/sessoes/" sid "/folha"))
(defn- url-listar [sid] (str "/sessoes/" sid "/folhas"))
(defn- url-html [sid v] (str "/sessoes/" sid "/folhas/" v))
(defn- url-pdf [sid v] (str "/sessoes/" sid "/folhas/" v "/pdf"))

;; ---------- caminho feliz — as 4 rotas ----------

(deftest feliz-congelar-listar-ler-html-e-pdf
  (let [ente (random-uuid) sid (random-uuid) ident (random-uuid)
        repo-s (fake-repo-sessoes {:sessao-fn (fn [_ id] (sessao-encerrada ente id))
                                   :folha-fn (fn [_ id] (lido-fechada ente id))})
        svc (service-fn* #{"secretario"} repo-s)
        auth (com-auth (token ente ident))

        r-post (pt/response-for svc :post (url-gerar sid) :headers auth)
        post-body (ler-json r-post)]
    (is (= 201 (:status r-post)))
    (is (= 1 (:versao post-body)))
    (is (= "folha-sessao-v1" (:spec-versao post-body)))
    (is (str/starts-with? (:html-hash post-body) "sha256:"))
    (is (str/starts-with? (:pdf-hash post-body) "sha256:"))
    (is (nil? (:ja-congelada post-body)) "primeira geracao nunca traz o sinalizador de dedup")

    (let [r-listar (pt/response-for svc :get (url-listar sid) :headers auth)
          listar-body (ler-json r-listar)]
      (is (= 200 (:status r-listar)))
      (is (= (str sid) (:sessao-id listar-body)))
      (is (= 1 (count (:folhas listar-body))))
      (is (= post-body (first (:folhas listar-body))) "a lista e' o MESMO documento de metadados do POST"))

    (let [r-html (pt/response-for svc :get (url-html sid 1) :headers auth)]
      (is (= 200 (:status r-html)))
      (is (str/starts-with? (get-in r-html [:headers "Content-Type"]) "text/html"))
      (is (= (:html-hash post-body) (sha256-hex (.getBytes ^String (:body r-html) "UTF-8")))
          "TESTE CENTRAL DA FATIA: o GET devolve byte a byte o que foi congelado — o hash bate"))

    (let [r-pdf (pt/response-for svc :get (url-pdf sid 1) :headers auth)]
      (is (= 200 (:status r-pdf)))
      (is (= "application/pdf" (get-in r-pdf [:headers "Content-Type"])))
      (is (re-find #"attachment; filename=\"folha-" (get-in r-pdf [:headers "Content-Disposition"])))
      (is (pos? (count (:body r-pdf)))))))

;; ---------- 401 / 403 ----------

(deftest sem-token-401
  (let [repo-s (fake-repo-sessoes {:sessao-fn (fn [_ _] nil) :folha-fn (fn [_ _] nil)})
        r (pt/response-for (service-fn* #{"secretario"} repo-s) :post (url-gerar (random-uuid)))]
    (is (= 401 (:status r)))))

(deftest papel-errado-403-nas-4-rotas
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes {:sessao-fn (fn [_ id] (sessao-encerrada ente id))
                                   :folha-fn (fn [_ id] (lido-fechada ente id))})
        svc (service-fn* #{} repo-s)
        auth (com-auth (token ente (random-uuid)))]
    (is (= 403 (:status (pt/response-for svc :post (url-gerar sid) :headers auth))))
    (is (= 403 (:status (pt/response-for svc :get (url-listar sid) :headers auth))))
    (is (= 403 (:status (pt/response-for svc :get (url-html sid 1) :headers auth))))
    (is (= 403 (:status (pt/response-for svc :get (url-pdf sid 1) :headers auth))))))

(deftest sessao-secreta-nao-vaza-pela-rota-de-leitura
  ;; o mesmo ator (SEM 'secretario') que o telao deixaria ver o QUORUM (rota magra) NAO pode ler a folha —
  ;; a folha nunca herda o gate magro, nem nas rotas de leitura.
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes {:sessao-fn (fn [_ id] (sessao-secreta ente id))
                                   :folha-fn (fn [_ id] (lido-secreta ente id))})
        svc (service-fn* #{} repo-s)
        auth (com-auth (token ente (random-uuid)))]
    (is (= 403 (:status (pt/response-for svc :get (url-listar sid) :headers auth))))
    (is (= 403 (:status (pt/response-for svc :get (url-html sid 1) :headers auth))))))

;; ---------- 404 ----------

(deftest sessao-inexistente-404
  (let [repo-s (fake-repo-sessoes {:sessao-fn (fn [_ _] nil) :folha-fn (fn [_ _] nil)})
        svc (service-fn* #{"secretario"} repo-s)
        auth (com-auth (token (random-uuid) (random-uuid)))
        sid (random-uuid)]
    (is (= 404 (:status (pt/response-for svc :post (url-gerar sid) :headers auth))))
    (is (= 404 (:status (pt/response-for svc :get (url-listar sid) :headers auth))))
    (is (= 404 (:status (pt/response-for svc :get (url-html sid 1) :headers auth))))))

(deftest cross-tenant-404-nunca-403
  ;; sessao existe, mas de OUTRO ente — o fake so' a devolve para o ente dono (RLS real faria o mesmo).
  (let [dono (random-uuid) intruso (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes {:sessao-fn (fn [ente id] (when (= ente dono) (sessao-encerrada dono id)))
                                   :folha-fn (fn [ente id] (when (= ente dono) (lido-fechada dono id)))})
        svc (service-fn* #{"secretario"} repo-s)
        auth (com-auth (token intruso (random-uuid)))]
    (is (= 404 (:status (pt/response-for svc :post (url-gerar sid) :headers auth)))
        "cross-tenant nunca confirma existencia com 403 — sempre 404")
    (is (= 404 (:status (pt/response-for svc :get (url-listar sid) :headers auth))))))

(deftest versao-inexistente-404
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes {:sessao-fn (fn [_ id] (sessao-encerrada ente id))
                                   :folha-fn (fn [_ id] (lido-fechada ente id))})
        svc (service-fn* #{"secretario"} repo-s)
        auth (com-auth (token ente (random-uuid)))]
    (pt/response-for svc :post (url-gerar sid) :headers auth) ; congela a v1
    (is (= 404 (:status (pt/response-for svc :get (url-html sid 99) :headers auth))))
    (is (= 404 (:status (pt/response-for svc :get (url-pdf sid 99) :headers auth))))))

;; ---------- 409 ----------

(deftest sessao-nao-fechada-409-d6
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes {:sessao-fn (fn [_ id] (sessao-aberta ente id))
                                   :folha-fn (fn [_ id] (lido-aberta ente id))})
        svc (service-fn* #{"secretario"} repo-s)
        auth (com-auth (token ente (random-uuid)))
        r (pt/response-for svc :post (url-gerar sid) :headers auth)]
    (is (= 409 (:status r)))
    (is (re-find #"FECHADA" (:erro (ler-json r))))))

(deftest segunda-colisao-de-23505-vira-409-traduzido
  ;; o RETRY UNICO do controller absorve a PRIMEIRA colisao; a fake sempre colide, entao a SEGUNDA (a que o
  ;; controller nao re-tenta) sobe crua ate' a borda — e' esse PSQLException que o handler tem de traduzir.
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes {:sessao-fn (fn [_ id] (sessao-encerrada ente id))
                                   :folha-fn (fn [_ id] (lido-fechada ente id))
                                   :inserir-dedup-fn (fn [_ _]
                                                        (throw (PSQLException. "duplicate"
                                                                               PSQLState/UNIQUE_VIOLATION)))})
        svc (service-fn* #{"secretario"} repo-s)
        auth (com-auth (token ente (random-uuid)))
        r (pt/response-for svc :post (url-gerar sid) :headers auth)]
    (is (= 409 (:status r)) "nunca o 500 generico — a borda traduz a SEGUNDA colisao")))

;; ---------- 400 — path-params malformados ----------

(deftest id-malformado-400
  (let [repo-s (fake-repo-sessoes {:sessao-fn (fn [_ _] nil) :folha-fn (fn [_ _] nil)})
        svc (service-fn* #{"secretario"} repo-s)
        auth (com-auth (token (random-uuid) (random-uuid)))]
    (is (= 400 (:status (pt/response-for svc :post "/sessoes/nao-e-uuid/folha" :headers auth))))))

(deftest versao-malformada-400
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes {:sessao-fn (fn [_ id] (sessao-encerrada ente id))
                                   :folha-fn (fn [_ id] (lido-fechada ente id))})
        svc (service-fn* #{"secretario"} repo-s)
        auth (com-auth (token ente (random-uuid)))]
    (is (= 400 (:status (pt/response-for svc :get (str "/sessoes/" sid "/folhas/nao-e-numero") :headers auth))))
    (is (= 400 (:status (pt/response-for svc :get (str "/sessoes/" sid "/folhas/0") :headers auth)))
        "versao 0 (ou negativa) e' invalida — a folha comeca em 1 (D7)")))
