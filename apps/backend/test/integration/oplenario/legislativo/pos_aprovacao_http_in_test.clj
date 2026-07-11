(ns oplenario.legislativo.pos-aprovacao-http-in-test
  "Onda B Slice 7 — a borda HTTP do POS-APROVACAO (F3.8a): POST gerar autografo, GET leitura composta,
  POST registrar resposta do Executivo, POST apreciar veto. DB-free (Repo FAKE, reify parcial) — mesmo
  racional de expediente-http-in-test/proposicao-escrita-http-in-test: os Repo reais (db/autografo,
  db/tramitacao-executiva, a composicao gerar-autografo-e-abrir-tramitacao!) ja tem cobertura de
  integracao real em pos_aprovacao_db_test.clj/pos_aprovacao_repo_test.clj."
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
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.rotas :as rotas]))

(defn- proposicao-canonica [ente id]
  {:id id :ente-id ente :tipo "projeto_lei" :ano 2026 :sequencial 22 :urn-lex "urn:fixture"
   :ementa "Dispoe sobre X [FIXTURE]" :estado "aprovada" :lock-version 0
   :atualizado-em (java.time.Instant/parse "2026-06-01T00:00:00Z")})

(defn- autografo-canonico [ente id pid & {:keys [numero] :or {numero 1}}]
  {:id id :ente-id ente :proposicao-id pid :numero numero :ano 2026
   :texto-versao-id (random-uuid) :destinatario-texto "Prefeito Municipal de Fortaleza [FIXTURE]"
   :destinatario-id nil :enviado-em (java.time.Instant/parse "2026-06-18T00:00:00Z")
   :prazo-resposta-em nil})

(defn- tramitacao-canonica [ente id autografo-id & {:keys [estado veto-tipo veto-razoes veto-votacao-id
                                                            lock-version]
                                                     :or {estado "aguardando" lock-version 0}}]
  {:id id :ente-id ente :autografo-id autografo-id :estado estado :veto-tipo veto-tipo
   :veto-razoes veto-razoes :veto-votacao-id veto-votacao-id :respondido-em nil :apreciado-em nil
   :lock-version lock-version})

(defn- fake-repo-legislativo
  "RepoLegislativo fake (parcial proposital — so' os metodos da vertical de pos-aprovacao + o pre-check de
  proposicao que buscar-pos-aprovacao/gerar-autografo fazem via buscar-proposicao-detalhe). A AUSENCIA de
  uma chave e' PROPOSITAL: se o handler chamar o metodo fora de ordem, a chamada nil estoura — sinaliza a
  regressao em vez de passar silenciosamente."
  [{:keys [buscar-proposicao-detalhe autografo-da-proposicao gerar-autografo-e-abrir-tramitacao!
           buscar-pos-aprovacao tramitacao-executiva-do-autografo registrar-resposta-executivo!
           buscar-tramitacao-executiva apreciar-veto!]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-leg/RepoLegislativo
    (buscar-proposicao-detalhe [_ _ente-id id] (buscar-proposicao-detalhe id))
    (autografo-da-proposicao [_ _ente-id pid] (autografo-da-proposicao pid))
    (gerar-autografo-e-abrir-tramitacao! [_ _ente-id m] (gerar-autografo-e-abrir-tramitacao! m))
    (buscar-pos-aprovacao [_ _ente-id pid] (buscar-pos-aprovacao pid))
    (tramitacao-executiva-do-autografo [_ _ente-id aid] (tramitacao-executiva-do-autografo aid))
    (registrar-resposta-executivo! [_ _ente-id m] (registrar-resposta-executivo! m))
    (buscar-tramitacao-executiva [_ _ente-id id] (buscar-tramitacao-executiva id))
    (apreciar-veto! [_ _ente-id m] (apreciar-veto! m))))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id] {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- fake-repo-cadastros
  "So' o metodo exercido por `gerar-autografo-handler` (`resolver-municipio` via `uf-e-municipio` — mesmo
  racional de proposicao-escrita-http-in-test/fake-repo-cadastros): 'Prefeito Municipal de Fortaleza'."
  []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cadastros-comp/RepoCadastros
    (uf-e-municipio [_ _ente-id] {:uf "CE" :municipio-nome "Fortaleza"})))

(defn- service-fn [papeis repo-l]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-legislativo repo-l
                                   :repo-cadastros (fake-repo-cadastros)})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id] (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))
(defn- com-bearer [tok] {"authorization" (str "Bearer " tok) "Content-Type" "application/json"})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

;; ========================= POST /legislativo/proposicoes/:id/autografo =========================

(deftest gerar-autografo-201
  (let [ente (random-uuid) pid (random-uuid) aid (random-uuid) tid (random-uuid)
        repo (fake-repo-legislativo
              {:buscar-proposicao-detalhe (fn [_id] {:proposicao (proposicao-canonica ente pid) :texto nil})
               :autografo-da-proposicao (fn [_pid] nil)
               :gerar-autografo-e-abrir-tramitacao! (fn [_m] {:autografo-id aid :numero 1 :tramitacao-executiva-id tid})
               :buscar-pos-aprovacao (fn [_pid] {:autografo (autografo-canonico ente aid pid)
                                                  :tramitacao-executiva (tramitacao-canonica ente tid aid)})})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post (str "/legislativo/proposicoes/" pid "/autografo")
                           :headers (com-bearer (token ente (random-uuid)))
                           :body (json/write-value-as-string {}))
        corpo (ler-json r)]
    (is (= 201 (:status r)))
    (is (= 1 (:numero (:autografo corpo))))
    (is (= "aguardando" (:estado (:tramitacao-executiva corpo))))))

(deftest gerar-autografo-proposicao-inexistente-404
  (let [repo (fake-repo-legislativo {:buscar-proposicao-detalhe (fn [_id] {:proposicao nil :texto nil})})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post (str "/legislativo/proposicoes/" (random-uuid) "/autografo")
                           :headers (com-bearer (token (random-uuid) (random-uuid)))
                           :body (json/write-value-as-string {}))]
    (is (= 404 (:status r)))))

(deftest gerar-autografo-duplicado-400
  ;; a proposicao ja' tem autografo (autografo-da-proposicao devolve nao-nil) — o controller lanca
  ;; :validacao/invalido ANTES de qualquer escrita nova (guard, mesmo racional de encerrar-votacao).
  (let [ente (random-uuid) pid (random-uuid)
        repo (fake-repo-legislativo
              {:buscar-proposicao-detalhe (fn [_id] {:proposicao (proposicao-canonica ente pid) :texto nil})
               :autografo-da-proposicao (fn [_pid] (autografo-canonico ente (random-uuid) pid))})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post (str "/legislativo/proposicoes/" pid "/autografo")
                           :headers (com-bearer (token ente (random-uuid)))
                           :body (json/write-value-as-string {}))]
    (is (= 400 (:status r)))))

(deftest gerar-autografo-corpo-invalido-400
  ;; `prazo-resposta-em` que nao parseia como Instant ISO-8601 -> 400 na borda, ANTES de qualquer Repo (fake
  ;; sem metodos: se o handler chamasse o Repo mesmo assim, a chamada nil estouraria em vez de 400).
  (let [repo (fake-repo-legislativo {})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post (str "/legislativo/proposicoes/" (random-uuid) "/autografo")
                           :headers (com-bearer (token (random-uuid) (random-uuid)))
                           :body (json/write-value-as-string {:prazo-resposta-em "nao-e-data"}))]
    (is (= 400 (:status r)))))

(deftest gerar-autografo-sem-papel-403
  (let [repo (fake-repo-legislativo {})
        r (pt/response-for (service-fn #{"vereador"} repo)
                           :post (str "/legislativo/proposicoes/" (random-uuid) "/autografo")
                           :headers (com-bearer (token (random-uuid) (random-uuid)))
                           :body (json/write-value-as-string {}))]
    (is (= 403 (:status r)))))

;; ========================= GET /legislativo/proposicoes/:id/pos-aprovacao =========================

(deftest pos-aprovacao-sem-autografo-ainda-200
  (let [ente (random-uuid) pid (random-uuid)
        repo (fake-repo-legislativo
              {:buscar-proposicao-detalhe (fn [_id] {:proposicao (proposicao-canonica ente pid) :texto nil})
               :buscar-pos-aprovacao (fn [_pid] {:autografo nil :tramitacao-executiva nil})})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :get (str "/legislativo/proposicoes/" pid "/pos-aprovacao")
                           :headers (com-bearer (token ente (random-uuid))))
        corpo (ler-json r)]
    (is (= 200 (:status r)))
    (is (nil? (:autografo corpo)))
    (is (nil? (:tramitacao-executiva corpo)))))

(deftest pos-aprovacao-com-autografo-e-tramitacao-200
  (let [ente (random-uuid) pid (random-uuid) aid (random-uuid) tid (random-uuid)
        repo (fake-repo-legislativo
              {:buscar-proposicao-detalhe (fn [_id] {:proposicao (proposicao-canonica ente pid) :texto nil})
               :buscar-pos-aprovacao (fn [_pid] {:autografo (autografo-canonico ente aid pid)
                                                  :tramitacao-executiva (tramitacao-canonica ente tid aid)})})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :get (str "/legislativo/proposicoes/" pid "/pos-aprovacao")
                           :headers (com-bearer (token ente (random-uuid))))
        corpo (ler-json r)]
    (is (= 200 (:status r)))
    (is (= 1 (:numero (:autografo corpo))))
    (is (= "aguardando" (:estado (:tramitacao-executiva corpo))))))

(deftest pos-aprovacao-proposicao-inexistente-404
  (let [repo (fake-repo-legislativo {:buscar-proposicao-detalhe (fn [_id] {:proposicao nil :texto nil})})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :get (str "/legislativo/proposicoes/" (random-uuid) "/pos-aprovacao")
                           :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 404 (:status r)))))

(deftest pos-aprovacao-sem-papel-403
  (let [repo (fake-repo-legislativo {})
        r (pt/response-for (service-fn #{"vereador"} repo)
                           :get (str "/legislativo/proposicoes/" (random-uuid) "/pos-aprovacao")
                           :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 403 (:status r)))))

;; ========================= POST /legislativo/autografos/:id/resposta =========================

(deftest registrar-resposta-sancionado-200
  ;; `tramitacao-executiva-do-autografo` e' chamada DUAS vezes pelo diplomat (pre-check/resolucao do id
  ;; DENTRO do controller + a RE-LEITURA final p/ a resposta) — o fake devolve direto o desfecho, mesma
  ;; simplificacao de detalhe-documento-com-protocolo-achata-numero-e-ano.
  (let [ente (random-uuid) aid (random-uuid) tid (random-uuid)
        repo (fake-repo-legislativo
              {:tramitacao-executiva-do-autografo (fn [_aid] (tramitacao-canonica ente tid aid :estado "sancionado"))
               :registrar-resposta-executivo! (fn [_m] {:id tid :estado "sancionado"})})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post (str "/legislativo/autografos/" aid "/resposta")
                           :headers (com-bearer (token ente (random-uuid)))
                           :body (json/write-value-as-string {:lock-version 0 :resultado "sancionado"}))]
    (is (= 200 (:status r)))
    (is (= "sancionado" (:estado (ler-json r))))))

(deftest registrar-resposta-vetado-sem-veto-tipo-400
  ;; wire/in barra ANTES de qualquer Repo — resultado 'vetado' sem veto-tipo e' corpo invalido (fake sem
  ;; registrar-resposta-executivo!: se o handler chamasse mesmo assim, estouraria em vez de 400).
  (let [ente (random-uuid) aid (random-uuid) tid (random-uuid)
        repo (fake-repo-legislativo
              {:tramitacao-executiva-do-autografo (fn [_aid] (tramitacao-canonica ente tid aid))})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post (str "/legislativo/autografos/" aid "/resposta")
                           :headers (com-bearer (token ente (random-uuid)))
                           :body (json/write-value-as-string {:lock-version 0 :resultado "vetado"}))]
    (is (= 400 (:status r)))))

(deftest registrar-resposta-vetado-com-veto-tipo-200
  (let [ente (random-uuid) aid (random-uuid) tid (random-uuid)
        repo (fake-repo-legislativo
              {:tramitacao-executiva-do-autografo
               (fn [_aid] (tramitacao-canonica ente tid aid :estado "vetado" :veto-tipo "total"
                                                :veto-razoes "Inconstitucional"))
               :registrar-resposta-executivo! (fn [_m] {:id tid :estado "vetado"})})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post (str "/legislativo/autografos/" aid "/resposta")
                           :headers (com-bearer (token ente (random-uuid)))
                           :body (json/write-value-as-string
                                   {:lock-version 0 :resultado "vetado" :veto-tipo "total"
                                    :veto-razoes "Inconstitucional"}))
        corpo (ler-json r)]
    (is (= 200 (:status r)))
    (is (= "vetado" (:estado corpo)))
    (is (= "total" (:veto-tipo corpo)))))

(deftest registrar-resposta-sem-lock-version-400
  (let [repo (fake-repo-legislativo {})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post (str "/legislativo/autografos/" (random-uuid) "/resposta")
                           :headers (com-bearer (token (random-uuid) (random-uuid)))
                           :body (json/write-value-as-string {:resultado "sancionado"}))]
    (is (= 400 (:status r)))))

(deftest registrar-resposta-autografo-sem-tramitacao-404
  (let [repo (fake-repo-legislativo {:tramitacao-executiva-do-autografo (fn [_aid] nil)})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post (str "/legislativo/autografos/" (random-uuid) "/resposta")
                           :headers (com-bearer (token (random-uuid) (random-uuid)))
                           :body (json/write-value-as-string {:lock-version 0 :resultado "sancionado"}))]
    (is (= 404 (:status r)))))

(deftest registrar-resposta-conflito-lock-version-400
  (let [ente (random-uuid) aid (random-uuid) tid (random-uuid)
        repo (fake-repo-legislativo
              {:tramitacao-executiva-do-autografo (fn [_aid] (tramitacao-canonica ente tid aid))
               :registrar-resposta-executivo!
               (fn [_m] (throw (ex-info "registrar-resposta!: conflito de lock_version ou inexistente"
                                       {:tipo :validacao/invalido :id tid :lock-version 0})))})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post (str "/legislativo/autografos/" aid "/resposta")
                           :headers (com-bearer (token ente (random-uuid)))
                           :body (json/write-value-as-string {:lock-version 0 :resultado "sancionado"}))]
    (is (= 400 (:status r)))))

(deftest registrar-resposta-sem-papel-403
  (let [repo (fake-repo-legislativo {})
        r (pt/response-for (service-fn #{"vereador"} repo)
                           :post (str "/legislativo/autografos/" (random-uuid) "/resposta")
                           :headers (com-bearer (token (random-uuid) (random-uuid)))
                           :body (json/write-value-as-string {:lock-version 0 :resultado "sancionado"}))]
    (is (= 403 (:status r)))))

;; ========================= POST /legislativo/tramitacoes-executivas/:id/apreciacao =========================

(deftest apreciar-veto-derrubado-200
  (let [ente (random-uuid) tid (random-uuid) aid (random-uuid) vid (random-uuid)
        repo (fake-repo-legislativo
              {:buscar-tramitacao-executiva
               (fn [_id]
                 ;; a 1a chamada (pre-check) devolve 'vetado'; apos escrever, a 2a (re-leitura p/ resposta)
                 ;; devolve o desfecho — o fake usa um atom p/ diferenciar as duas chamadas.
                 (tramitacao-canonica ente tid aid :estado "veto_derrubado" :veto-tipo "total"
                                       :veto-votacao-id vid :lock-version 2))
               :apreciar-veto! (fn [_m] {:id tid :estado "veto_derrubado"})})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post (str "/legislativo/tramitacoes-executivas/" tid "/apreciacao")
                           :headers (com-bearer (token ente (random-uuid)))
                           :body (json/write-value-as-string
                                   {:lock-version 1 :resultado "veto_derrubado" :veto-votacao-id (str vid)}))
        corpo (ler-json r)]
    (is (= 200 (:status r)))
    (is (= "veto_derrubado" (:estado corpo)))
    (is (= (str vid) (:veto-votacao-id corpo)))))

(deftest apreciar-veto-sem-veto-votacao-id-400
  (let [repo (fake-repo-legislativo {:buscar-tramitacao-executiva (fn [_id] {:id (random-uuid)})})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post (str "/legislativo/tramitacoes-executivas/" (random-uuid) "/apreciacao")
                           :headers (com-bearer (token (random-uuid) (random-uuid)))
                           :body (json/write-value-as-string {:lock-version 1 :resultado "veto_mantido"}))]
    (is (= 400 (:status r)))))

(deftest apreciar-veto-inexistente-404
  (let [repo (fake-repo-legislativo {:buscar-tramitacao-executiva (fn [_id] nil)})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post (str "/legislativo/tramitacoes-executivas/" (random-uuid) "/apreciacao")
                           :headers (com-bearer (token (random-uuid) (random-uuid)))
                           :body (json/write-value-as-string
                                   {:lock-version 1 :resultado "veto_mantido" :veto-votacao-id (str (random-uuid))}))]
    (is (= 404 (:status r)))))

(deftest apreciar-veto-sem-papel-403
  (let [repo (fake-repo-legislativo {})
        r (pt/response-for (service-fn #{"vereador"} repo)
                           :post (str "/legislativo/tramitacoes-executivas/" (random-uuid) "/apreciacao")
                           :headers (com-bearer (token (random-uuid) (random-uuid)))
                           :body (json/write-value-as-string
                                   {:lock-version 1 :resultado "veto_mantido" :veto-votacao-id (str (random-uuid))}))]
    (is (= 403 (:status r)))))
