(ns oplenario.legislativo.votacao-http-in-test
  "Slice 3 da votacao ao vivo (wire/W3) — a vertical de rota que DIRIGE a votacao ao vivo: abrir / registrar
  voto / encerrar. A rota mora no `legislativo` (DONO do agregado votacao + da tx que casa ato+emissao, Slice 1),
  nao no `sessoes` — espelha o SSE `/sessoes/:id/plenario` que ja mora no `tempo_real` (prefixo de URL != dono do
  modulo). A authz e' HERDADA do recurso sessao (mesma Casa, fail-closed) via `consultar-sessao` INJETADA pelo
  host (legislativo NAO importa sessoes, §22.10). DB-free: RepoLegislativo + RepoSessoes FAKE (reify) + idp-dev
  real, como o http-in-test de sessoes (W3) — os Repo reais ja tem cobertura no F3/F4."
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.rotas :as rotas]
            [oplenario.sessoes.components.repositorio :as repo-sessoes]))

(defn- sessao-canonica
  "Sessao como `consultar-sessao` (delega ao Repo de sessoes) devolve — so o que a authz le (ente-id)."
  [ente-id id]
  {:id id :ente-id ente-id :estado "aberta" :transmite-publica true})

(defn- sessao-encerrada
  "T2 grupo A achado #4/#5 (ledger Fase 8): a MESMA sessao, mas ja FECHADA — `service-fn*` monta via
  `rotas/montar`, que injeta o `sessao-fechada?` REAL (`sessoes.logic/estados-sessao-fechada`), entao estes
  testes exercitam a wiring de producao completa, nao um stub local."
  [ente-id id]
  (assoc (sessao-canonica ente-id id) :estado "encerrada"))

(defn- votacao-canonica
  "Votacao como `buscar-votacao` devolve (kebab). Carrega :modalidade (p/ o dispatch nominal<->secreta) e
  :sessao-id (p/ a amarra votacao<->sessao da URL)."
  [ente-id id sessao-id modalidade]
  {:id id :ente-id ente-id :sessao-id sessao-id :modalidade modalidade
   :objeto-tipo "proposicao" :objeto-id (random-uuid) :quorum-tipo "maioria_simples"
   :estado "aberta" :lock-version 0})

(defn- fake-repo-sessoes
  "RepoSessoes fake: so `buscar-sessao` (a authz da votacao mora no recurso sessao, via consultar-sessao)."
  [busca-fn]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ ente-id id] (busca-fn ente-id id))))

(defn- fake-repo-legislativo
  "RepoLegislativo fake (parcial proposital — so os metodos da vertical de votacao). `busca-votacao-fn` resolve
  `buscar-votacao`; os escritores ECOAM um recibo e GRAVAM no `chamadas` (atom) qual foi exercido — p/ provar o
  dispatch nominal->registrar-voto! / secreta->registrar-voto-secreto!."
  [busca-votacao-fn chamadas]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-leg/RepoLegislativo
    (buscar-votacao [_ ente-id id] (busca-votacao-fn ente-id id))
    (abrir-votacao! [_ _ente-id m]
      (swap! chamadas conj :abrir) {:id (:id m) :lock-version 0})
    (registrar-voto! [_ _ente-id m]
      (swap! chamadas conj :nominal) {:id (:id m)})
    (registrar-voto-secreto! [_ _ente-id m]
      (swap! chamadas conj :secreto) {:id (:id m)})
    (encerrar-votacao! [_ _ente-id m]
      (swap! chamadas conj :encerrar)
      {:id (:id m) :estado "encerrada" :resultado "aprovada"
       :sim 6 :nao 3 :abstencao 1 :base-membros 11})))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- service-fn*
  [papeis repo-s repo-l]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-sessoes repo-s
                                   :repo-legislativo repo-l})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id]
  (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))

(defn- com-json [tok] {"authorization" (str "Bearer " tok) "Content-Type" "application/json"})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

;; ---------- POST /sessoes/:id/votacoes — abrir votacao ----------

(defn- corpo-abrir []
  (json/write-value-as-string
   {:objeto-tipo "proposicao" :objeto-id (str (random-uuid))
    :modalidade "nominal" :quorum-tipo "maioria_simples"}))

(deftest abrir-votacao-201
  (let [ente (random-uuid) sid (random-uuid)
        chamadas (atom [])
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo (fn [_ _] nil) chamadas)
        r (pt/response-for (service-fn* #{"secretario"} repo-s repo-l)
                           :post (str "/sessoes/" sid "/votacoes")
                           :headers (com-json (token ente (random-uuid))) :body (corpo-abrir))
        body (ler-json r)]
    (is (= 201 (:status r)) "abrir votacao com papel + corpo valido + mesma Casa -> 201")
    (is (string? (:id body)) "o recibo carrega o id da votacao (string)")
    (is (= "aberta" (:estado body)) "votacao recem-aberta")
    (is (= 0 (:lock-version body))
        "ledger de prontidao Fase 8 achado #2: nao ha' GET de detalhe da votacao -- este recibo e' a UNICA fonte do lock-version que POST .../encerramento exige no corpo")
    (is (= [:abrir] @chamadas) "o controller chamou abrir-votacao! do Repo do legislativo")))

(deftest abrir-votacao-sem-papel-403
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo (fn [_ _] nil) (atom []))
        r (pt/response-for (service-fn* #{"vereador"} repo-s repo-l)
                           :post (str "/sessoes/" sid "/votacoes")
                           :headers (com-json (token ente (random-uuid))) :body (corpo-abrir))]
    (is (= 403 (:status r)) "ator sem papel 'secretario' -> authz grossa nega -> 403")))

(deftest abrir-votacao-casa-alheia-403
  ;; consultar-sessao devolve uma sessao de OUTRA Casa (escapou da RLS por bug hipotetico): a camada FINA
  ;; (pode-dirigir-votacao? = mesma Casa) tem de NEGAR -> 403.
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica (random-uuid) id)))
        repo-l (fake-repo-legislativo (fn [_ _] nil) (atom []))
        r (pt/response-for (service-fn* #{"secretario"} repo-s repo-l)
                           :post (str "/sessoes/" sid "/votacoes")
                           :headers (com-json (token ente (random-uuid))) :body (corpo-abrir))]
    (is (= 403 (:status r)) "sessao de ente alheio -> policy.check nega -> 403")))

(deftest abrir-votacao-sessao-inexistente-404
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil))
        repo-l (fake-repo-legislativo (fn [_ _] nil) (atom []))
        r (pt/response-for (service-fn* #{"secretario"} repo-s repo-l)
                           :post (str "/sessoes/" (random-uuid) "/votacoes")
                           :headers (com-json (token ente (random-uuid))) :body (corpo-abrir))]
    (is (= 404 (:status r)) "sessao inexistente no tenant -> 404 (sem sessao nao se dirige votacao)")))

(deftest abrir-votacao-sem-token-401
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo (fn [_ _] nil) (atom []))
        r (pt/response-for (service-fn* #{"secretario"} repo-s repo-l)
                           :post (str "/sessoes/" sid "/votacoes") :body (corpo-abrir))]
    (is (= 401 (:status r)) "rota herda a cadeia de auth: sem token -> 401 (fail-closed)")))

(deftest abrir-votacao-corpo-invalido-400
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo (fn [_ _] nil) (atom []))
        corpo (json/write-value-as-string {:objeto-tipo "proposicao"})  ; sem objeto-id/modalidade/quorum
        r (pt/response-for (service-fn* #{"secretario"} repo-s repo-l)
                           :post (str "/sessoes/" sid "/votacoes")
                           :headers (com-json (token ente (random-uuid))) :body corpo)]
    (is (= 400 (:status r)) "corpo que nao casa o wire/in -> 400 (validacao na borda), nunca 500")))

(deftest abrir-votacao-sessao-id-malformado-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [e i] (sessao-canonica e i)))
        repo-l (fake-repo-legislativo (fn [_ _] nil) (atom []))
        r (pt/response-for (service-fn* #{"secretario"} repo-s repo-l)
                           :post "/sessoes/nao-e-uuid/votacoes"
                           :headers (com-json (token ente (random-uuid))) :body (corpo-abrir))]
    (is (= 400 (:status r)) "path-param :id malformado -> 400, nunca 500")))

(deftest abrir-votacao-sessao-encerrada-409
  ;; T2 grupo A achado #5 (ledger Fase 8): verificado AO VIVO pelo Daouda — abrir votacao numa sessao ja
  ;; ENCERRADA devolvia 201. RED confirmado (ver relatorio): antes do fix em `sessao-autorizada`, este teste
  ;; falhava com `Expected: 409 Actual: 201` (a Mesa conseguia abrir votacao num capitulo fechado da ata).
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-encerrada ente sid)))
        repo-l (fake-repo-legislativo (fn [_ _] nil) (atom []))
        r (pt/response-for (service-fn* #{"secretario"} repo-s repo-l)
                           :post (str "/sessoes/" sid "/votacoes")
                           :headers (com-json (token ente (random-uuid))) :body (corpo-abrir))]
    (is (= 409 (:status r)) "sessao ja encerrada -> 409 (nao 201) — ata fechada nao admite votacao nova")))

;; ---------- POST /sessoes/:id/votacoes/:votacao-id/votos — registrar voto (dispatch por modalidade) ----------

(deftest registrar-voto-nominal-201
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        chamadas (atom [])
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo (fn [_ _] (votacao-canonica ente vid sid "nominal")) chamadas)
        corpo (json/write-value-as-string {:vereador-id (str (random-uuid)) :voto "sim"})
        r (pt/response-for (service-fn* #{"secretario"} repo-s repo-l)
                           :post (str "/sessoes/" sid "/votacoes/" vid "/votos")
                           :headers (com-json (token ente (random-uuid))) :body corpo)
        body (ler-json r)]
    (is (= 201 (:status r)) "voto nominal em votacao nominal da mesma sessao -> 201")
    (is (string? (:id body)) "recibo carrega o id do voto (string)")
    (is (not (some #{:secreto} @chamadas)) "NAO chamou o caminho secreto")
    (is (some #{:nominal} @chamadas) "dispatch: votacao nominal -> registrar-voto! (nominal)")))

(deftest registrar-voto-duplicado-mesa-409
  ;; T2 grupo A achado #1 (ledger Fase 8): a rota da Mesa (`voto-handler`) NAO tinha `try/catch` nenhum —
  ;; a PSQLException do UNIQUE subia crua ate' o interceptor global -> 500 ('erro interno'). Confirmado ao
  ;; vivo por `docker logs oplenario-app-1`: `PSQLException ... duplicate key value violates unique
  ;; constraint "votos_ente_id_votacao_id_vereador_id_key"`. Este teste (fake Repo, sem Postgres) prova a
  ;; TRADUCAO do diplomat: `:conflito/voto-duplicado` -> 409 (o UNIQUE em si, contra Postgres real, e' o
  ;; irmao `voto_duplicado_mesa_repo_test.clj`). RED confirmado (removendo o catch de `voto-handler`): a
  ;; excecao `:conflito/voto-duplicado` NAO tratada cai no `:else` do interceptor global -> 500, e este `is`
  ;; reprova com `Expected: 409 Actual: 500`.
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l #_{:clj-kondo/ignore [:missing-protocol-method]}
               (reify repo-leg/RepoLegislativo
                 (buscar-votacao [_ _ _] (votacao-canonica ente vid sid "nominal"))
                 (registrar-voto! [_ _ m]
                   (throw (ex-info "voto ja registrado para este vereador nesta votacao"
                                   {:tipo :conflito/voto-duplicado :votacao-id (:votacao-id m)}))))
        corpo (json/write-value-as-string {:vereador-id (str (random-uuid)) :voto "sim"})
        r (pt/response-for (service-fn* #{"secretario"} repo-s repo-l)
                           :post (str "/sessoes/" sid "/votacoes/" vid "/votos")
                           :headers (com-json (token ente (random-uuid))) :body corpo)]
    (is (= 409 (:status r)) "2o voto do mesmo vereador -> 409 (nunca 500 opaco)")))

(deftest registrar-voto-secreto-201
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        chamadas (atom [])
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo (fn [_ _] (votacao-canonica ente vid sid "secreta")) chamadas)
        corpo (json/write-value-as-string {:voto "sim"})
        r (pt/response-for (service-fn* #{"secretario"} repo-s repo-l)
                           :post (str "/sessoes/" sid "/votacoes/" vid "/votos")
                           :headers (com-json (token ente (random-uuid))) :body corpo)]
    (is (= 201 (:status r)) "voto em votacao secreta -> 201")
    (is (some #{:secreto} @chamadas) "dispatch: votacao secreta -> registrar-voto-secreto! (sem identidade)")
    (is (not (some #{:nominal} @chamadas)) "NAO chamou o caminho nominal (sigilo §22.6)")))

(deftest registrar-voto-nominal-sem-vereador-400
  ;; voto NOMINAL exige vereador-id; a modalidade so e' conhecida com a votacao carregada (o adapters/in nao
  ;; sabe), entao o guard mora no controller -> 400 (validacao), nunca 500 do NOT NULL do banco.
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo (fn [_ _] (votacao-canonica ente vid sid "nominal")) (atom []))
        corpo (json/write-value-as-string {:voto "sim"})  ; sem vereador-id
        r (pt/response-for (service-fn* #{"secretario"} repo-s repo-l)
                           :post (str "/sessoes/" sid "/votacoes/" vid "/votos")
                           :headers (com-json (token ente (random-uuid))) :body corpo)]
    (is (= 400 (:status r)) "voto nominal sem vereador-id -> 400 (guard no controller)")))

(deftest registrar-voto-votacao-de-outra-sessao-404
  ;; a votacao existe e e' da mesma Casa, mas pertence a OUTRA sessao (sessao-id != :id da URL): a amarra
  ;; votacao<->sessao tem de barrar (escopo de authz), traduzido 404 (nao vaza existencia cross-sessao).
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid) outra (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo (fn [_ _] (votacao-canonica ente vid outra "nominal")) (atom []))
        corpo (json/write-value-as-string {:vereador-id (str (random-uuid)) :voto "sim"})
        r (pt/response-for (service-fn* #{"secretario"} repo-s repo-l)
                           :post (str "/sessoes/" sid "/votacoes/" vid "/votos")
                           :headers (com-json (token ente (random-uuid))) :body corpo)]
    (is (= 404 (:status r)) "votacao de outra sessao -> 404 (amarra votacao<->sessao)")))

(deftest registrar-voto-votacao-inexistente-404
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo (fn [_ _] nil) (atom []))
        corpo (json/write-value-as-string {:vereador-id (str (random-uuid)) :voto "sim"})
        r (pt/response-for (service-fn* #{"secretario"} repo-s repo-l)
                           :post (str "/sessoes/" sid "/votacoes/" (random-uuid) "/votos")
                           :headers (com-json (token ente (random-uuid))) :body corpo)]
    (is (= 404 (:status r)) "votacao inexistente -> 404")))

(deftest registrar-voto-simbolica-400
  ;; 'simbolica' (aclamacao) NAO registra votos individuais — o dispatch case tem de barrar (nem nominal nem
  ;; secreto), -> 400, nunca cair no ramo nominal e dar 500 do guard de modalidade do Repo.
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo (fn [_ _] (votacao-canonica ente vid sid "simbolica")) (atom []))
        corpo (json/write-value-as-string {:vereador-id (str (random-uuid)) :voto "sim"})
        r (pt/response-for (service-fn* #{"secretario"} repo-s repo-l)
                           :post (str "/sessoes/" sid "/votacoes/" vid "/votos")
                           :headers (com-json (token ente (random-uuid))) :body corpo)]
    (is (= 400 (:status r)) "votacao simbolica nao registra voto individual -> 400 (case fail-closed)")))

(deftest registrar-voto-secreto-descarta-identidade
  ;; sigilo §22.6: ainda que o cliente envie vereador-id ao endpoint de uma votacao SECRETA, o controller
  ;; DESCARTA a identidade (e o autor) antes de chamar o Repo. Regressao guard do strip.
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        capturado (atom :nao-chamado)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l #_{:clj-kondo/ignore [:missing-protocol-method]}
               (reify repo-leg/RepoLegislativo
                 (buscar-votacao [_ _ _] (votacao-canonica ente vid sid "secreta"))
                 (registrar-voto-secreto! [_ _ m] (reset! capturado m) {:id (:id m)}))
        corpo (json/write-value-as-string {:voto "sim" :vereador-id (str (random-uuid))})
        r (pt/response-for (service-fn* #{"secretario"} repo-s repo-l)
                           :post (str "/sessoes/" sid "/votacoes/" vid "/votos")
                           :headers (com-json (token ente (random-uuid))) :body corpo)]
    (is (= 201 (:status r)) "voto secreto -> 201 mesmo com vereador-id no corpo")
    (is (not (contains? @capturado :vereador-id)) "o controller descartou :vereador-id antes do Repo (sigilo)")
    (is (not (contains? @capturado :created-by)) "tambem descartou :created-by (autor)")))

(deftest registrar-voto-votacao-id-malformado-400
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo (fn [_ _] nil) (atom []))
        corpo (json/write-value-as-string {:vereador-id (str (random-uuid)) :voto "sim"})
        r (pt/response-for (service-fn* #{"secretario"} repo-s repo-l)
                           :post (str "/sessoes/" sid "/votacoes/nao-e-uuid/votos")
                           :headers (com-json (token ente (random-uuid))) :body corpo)]
    (is (= 400 (:status r)) "path-param :votacao-id malformado -> 400, nunca 500")))

(deftest registrar-voto-sessao-encerrada-409
  ;; T2 grupo A achado #4/#5 (ledger Fase 8): registrar voto NUMA sessao ja encerrada tinha o MESMO buraco
  ;; que abrir-votacao — `sessao-autorizada` e' o unico ponto de checagem para as 4 escritas da familia.
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-encerrada ente sid)))
        repo-l (fake-repo-legislativo (fn [_ _] (votacao-canonica ente vid sid "nominal")) (atom []))
        corpo (json/write-value-as-string {:vereador-id (str (random-uuid)) :voto "sim"})
        r (pt/response-for (service-fn* #{"secretario"} repo-s repo-l)
                           :post (str "/sessoes/" sid "/votacoes/" vid "/votos")
                           :headers (com-json (token ente (random-uuid))) :body corpo)]
    (is (= 409 (:status r)) "sessao ja encerrada -> 409, mesmo com votacao 'aberta'")))

;; ---------- POST /sessoes/:id/votacoes/:votacao-id/encerramento — encerrar ----------

(deftest encerrar-votacao-200
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        chamadas (atom [])
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo (fn [_ _] (votacao-canonica ente vid sid "nominal")) chamadas)
        corpo (json/write-value-as-string {:base-membros 11 :lock-version 0})
        r (pt/response-for (service-fn* #{"secretario"} repo-s repo-l)
                           :post (str "/sessoes/" sid "/votacoes/" vid "/encerramento")
                           :headers (com-json (token ente (random-uuid))) :body corpo)
        body (ler-json r)]
    (is (= 200 (:status r)) "encerrar votacao da mesma sessao -> 200")
    (is (= "encerrada" (:estado body)))
    (is (= "aprovada" (:resultado body)))
    (is (= 6 (:total-sim body)) "totais projetados (sim->total-sim)")
    (is (= 11 (:base-membros body)))
    (is (some #{:encerrar} @chamadas) "chamou encerrar-votacao! do Repo")))

(deftest encerrar-votacao-de-outra-sessao-404
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid) outra (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo (fn [_ _] (votacao-canonica ente vid outra "nominal")) (atom []))
        corpo (json/write-value-as-string {:base-membros 11 :lock-version 0})
        r (pt/response-for (service-fn* #{"secretario"} repo-s repo-l)
                           :post (str "/sessoes/" sid "/votacoes/" vid "/encerramento")
                           :headers (com-json (token ente (random-uuid))) :body corpo)]
    (is (= 404 (:status r)) "encerrar votacao de outra sessao -> 404 (amarra votacao<->sessao)")))

(deftest encerrar-votacao-sem-papel-403
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo (fn [_ _] (votacao-canonica ente vid sid "nominal")) (atom []))
        corpo (json/write-value-as-string {:base-membros 11 :lock-version 0})
        r (pt/response-for (service-fn* #{"vereador"} repo-s repo-l)
                           :post (str "/sessoes/" sid "/votacoes/" vid "/encerramento")
                           :headers (com-json (token ente (random-uuid))) :body corpo)]
    (is (= 403 (:status r)) "ator sem papel 'secretario' -> 403")))

(deftest encerrar-votacao-simbolica-sem-resultado-400
  ;; modalidade 'simbolica' apura por aclamacao: exige :resultado no corpo. Sem ele o controller barra na borda
  ;; (-> 400) usando a votacao carregada, em vez de propagar como 500 do db (sec MEDIUM-2).
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo (fn [_ _] (votacao-canonica ente vid sid "simbolica")) (atom []))
        corpo (json/write-value-as-string {:lock-version 0})  ; sem resultado
        r (pt/response-for (service-fn* #{"secretario"} repo-s repo-l)
                           :post (str "/sessoes/" sid "/votacoes/" vid "/encerramento")
                           :headers (com-json (token ente (random-uuid))) :body corpo)]
    (is (= 400 (:status r)) "encerrar simbolica sem resultado -> 400, nunca 500")))

(deftest encerrar-votacao-ja-terminal-409
  ;; T2 grupo A achado #3 (ledger de prontidao Fase 8): votacao ja 'encerrada'/'anulada' nao reencerra — o
  ;; controller barra com a votacao carregada, em vez de propagar o ex-info do trigger/db como 500
  ;; (sec LOW-1). ERA `:validacao/invalido` (-> 400): errado, porque 400 diz 'conserte seu pedido' e o
  ;; MESMISSIMO corpo teria funcionado segundos antes — o pedido sempre foi valido, o que mudou foi o
  ;; ESTADO do recurso. Corrigido p/ `:conflito/votacao-terminal` (-> 409), consistente com os outros 5
  ;; conflitos de 'ja terminal' deste MESMO grupo de 17 rotas (transicao/pauta/inscricao/fala/vinculo).
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        v-terminal (assoc (votacao-canonica ente vid sid "nominal") :estado "encerrada")
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo (fn [_ _] v-terminal) (atom []))
        corpo (json/write-value-as-string {:base-membros 11 :lock-version 0})
        r (pt/response-for (service-fn* #{"secretario"} repo-s repo-l)
                           :post (str "/sessoes/" sid "/votacoes/" vid "/encerramento")
                           :headers (com-json (token ente (random-uuid))) :body corpo)]
    (is (= 409 (:status r)) "reencerrar votacao terminal -> 409 (conflito de estado, nao pedido invalido)")))

(deftest encerrar-votacao-id-malformado-400
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo (fn [_ _] nil) (atom []))
        corpo (json/write-value-as-string {:base-membros 11 :lock-version 0})
        r (pt/response-for (service-fn* #{"secretario"} repo-s repo-l)
                           :post (str "/sessoes/" sid "/votacoes/nao-e-uuid/encerramento")
                           :headers (com-json (token ente (random-uuid))) :body corpo)]
    (is (= 400 (:status r)) "path-param :votacao-id malformado -> 400, nunca 500")))

;; ---------- GET /sessoes/:id/votacoes/:votacao-id — O QUE esta em votacao (fatia 'demo-tres-consertos' #2) ----------
;; Achado ao vivo (Daouda, 12/09/2026): o cockpit do vereador (`/votar`) mostrava SO o placar — nenhuma
;; ementa, nenhum numero de materia. Esta rota resolve o objeto POLIMORFICO da votacao pra exibicao.

(defn- proposicao-canonica [id]
  {:id id :tipo "projeto_lei" :ano 2026 :sequencial 42 :ementa "Alter a Lei Organica quanto a Mesa Diretora"
   ;; campos QUE NAO devem atravessar pro wire (regressao guard, ver o teste `nunca-vaza-campos-extras`):
   :autor-id (random-uuid) :autor-tipo "vereador" :estado "em_pauta" :lock-version 3
   :atributos-especificos {:algum "dado interno"}})

(defn- fake-repo-legislativo-com-proposicao
  "RepoLegislativo fake pra' `detalhe-votacao`: `buscar-votacao` + `buscar-proposicao`."
  [votacao proposicao]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-leg/RepoLegislativo
    (buscar-votacao [_ _ente-id _id] votacao)
    (buscar-proposicao [_ _ente-id _id] proposicao)))

(deftest detalhe-votacao-proposicao-200
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid) pid (random-uuid)
        votacao (assoc (votacao-canonica ente vid sid "nominal") :objeto-tipo "proposicao" :objeto-id pid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo-com-proposicao votacao (proposicao-canonica pid))
        r (pt/response-for (service-fn* #{"vereador"} repo-s repo-l)
                           :get (str "/sessoes/" sid "/votacoes/" vid)
                           :headers (com-json (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= "proposicao" (:objeto-tipo body)))
    (is (= {:tipo "projeto_lei" :ano 2026 :sequencial 42
            :ementa "Alter a Lei Organica quanto a Mesa Diretora"}
           (:proposicao body))
        "o vereador ve tipo+ano+sequencial+ementa reais — nao o placar cego de antes")))

(deftest detalhe-votacao-redacao-final-200
  ;; `objetos-que-carregam-a-materia` (db/votacao.clj) inclui 'redacao_final': o `objeto-id` E' a propria
  ;; proposicao, mesma resolucao de 'proposicao'.
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid) pid (random-uuid)
        votacao (assoc (votacao-canonica ente vid sid "nominal") :objeto-tipo "redacao_final" :objeto-id pid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo-com-proposicao votacao (proposicao-canonica pid))
        r (pt/response-for (service-fn* #{"vereador"} repo-s repo-l)
                           :get (str "/sessoes/" sid "/votacoes/" vid)
                           :headers (com-json (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= "redacao_final" (:objeto-tipo body)))
    (is (= 42 (:sequencial (:proposicao body))))))

(deftest detalhe-votacao-emenda-sem-proposicao-honesto
  ;; 'emenda' e' entidade PROPRIA (objeto-id aponta outra tabela) — resolve-la por completo e' escopo maior
  ;; (registrado, nao feito aqui). O cliente recebe o TIPO (rotulo honesto), nunca um titulo vazio/inventado
  ;; nem uma tentativa de ler a proposicao com o id errado.
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        chamou-proposicao? (atom false)
        votacao (assoc (votacao-canonica ente vid sid "nominal") :objeto-tipo "emenda" :objeto-id (random-uuid))
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l #_{:clj-kondo/ignore [:missing-protocol-method]}
               (reify repo-leg/RepoLegislativo
                 (buscar-votacao [_ _ _] votacao)
                 (buscar-proposicao [_ _ _] (reset! chamou-proposicao? true) nil))
        r (pt/response-for (service-fn* #{"vereador"} repo-s repo-l)
                           :get (str "/sessoes/" sid "/votacoes/" vid)
                           :headers (com-json (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= "emenda" (:objeto-tipo body)))
    (is (nil? (:proposicao body)) "sem titulo inventado — o cliente monta o rotulo honesto do TIPO")
    (is (false? @chamou-proposicao?) "nem tenta ler proposicao pelo objeto-id de uma emenda — tabelas diferentes")))

(deftest detalhe-votacao-nunca-vaza-campos-extras
  ;; regressao: `buscar-proposicao` devolve o registro CHEIO (autor/estado/lock-version/atributos internos)
  ;; — o adapter tem de FILTRAR pro schema `:closed true`, nunca repassar o mapa inteiro.
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid) pid (random-uuid)
        votacao (assoc (votacao-canonica ente vid sid "nominal") :objeto-tipo "proposicao" :objeto-id pid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo-com-proposicao votacao (proposicao-canonica pid))
        r (pt/response-for (service-fn* #{"vereador"} repo-s repo-l)
                           :get (str "/sessoes/" sid "/votacoes/" vid)
                           :headers (com-json (token ente (random-uuid))))]
    (is (= 200 (:status r)))
    (is (not (re-find #"autor|atributos|lock.version|em_pauta" (:body r)))
        "so' tipo/ano/sequencial/ementa atravessam — nunca autor-id/estado/lock-version/atributos-especificos")))

(deftest detalhe-votacao-sem-papel-vereador-403
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo (fn [_ _] (votacao-canonica ente vid sid "nominal")) (atom []))
        r (pt/response-for (service-fn* #{"secretario"} repo-s repo-l)
                           :get (str "/sessoes/" sid "/votacoes/" vid)
                           :headers (com-json (token ente (random-uuid))))]
    (is (= 403 (:status r)) "so' 'vereador' — 'secretario' sozinho nao alcanca (mesmo gate de meu-voto)")))

(deftest detalhe-votacao-de-outra-sessao-404
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid) outra (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo (fn [_ _] (votacao-canonica ente vid outra "nominal")) (atom []))
        r (pt/response-for (service-fn* #{"vereador"} repo-s repo-l)
                           :get (str "/sessoes/" sid "/votacoes/" vid)
                           :headers (com-json (token ente (random-uuid))))]
    (is (= 404 (:status r)) "votacao de outra sessao -> 404 (amarra votacao<->sessao)")))

(deftest detalhe-votacao-inexistente-404
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo (fn [_ _] nil) (atom []))
        r (pt/response-for (service-fn* #{"vereador"} repo-s repo-l)
                           :get (str "/sessoes/" sid "/votacoes/" (random-uuid))
                           :headers (com-json (token ente (random-uuid))))]
    (is (= 404 (:status r)))))

(deftest detalhe-votacao-sessao-encerrada-409
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-encerrada ente sid)))
        repo-l (fake-repo-legislativo (fn [_ _] (votacao-canonica ente vid sid "nominal")) (atom []))
        r (pt/response-for (service-fn* #{"vereador"} repo-s repo-l)
                           :get (str "/sessoes/" sid "/votacoes/" vid)
                           :headers (com-json (token ente (random-uuid))))]
    (is (= 409 (:status r)) "sessao ja encerrada -> 409, mesmo gate das 4 escritas da familia")))

(deftest detalhe-votacao-votacao-id-malformado-400
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo (fn [_ _] nil) (atom []))
        r (pt/response-for (service-fn* #{"vereador"} repo-s repo-l)
                           :get (str "/sessoes/" sid "/votacoes/nao-e-uuid")
                           :headers (com-json (token ente (random-uuid))))]
    (is (= 400 (:status r)) "path-param :votacao-id malformado -> 400, nunca 500")))

(deftest detalhe-votacao-sem-token-401
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo (fn [_ _] (votacao-canonica ente vid sid "nominal")) (atom []))
        r (pt/response-for (service-fn* #{"vereador"} repo-s repo-l)
                           :get (str "/sessoes/" sid "/votacoes/" vid))]
    (is (= 401 (:status r)))))

(deftest encerrar-votacao-sessao-encerrada-409
  ;; T2 grupo A achado #4/#5 (ledger Fase 8): mesmo gate de `sessao-autorizada` — encerrar votacao numa
  ;; sessao ja ENCERRADA e' bloqueado ANTES de checar se a votacao em si e' terminal (achado #3, teste irmao
  ;; `encerrar-votacao-ja-terminal-409` acima).
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-encerrada ente sid)))
        repo-l (fake-repo-legislativo (fn [_ _] (votacao-canonica ente vid sid "nominal")) (atom []))
        corpo (json/write-value-as-string {:base-membros 11 :lock-version 0})
        r (pt/response-for (service-fn* #{"secretario"} repo-s repo-l)
                           :post (str "/sessoes/" sid "/votacoes/" vid "/encerramento")
                           :headers (com-json (token ente (random-uuid))) :body corpo)]
    (is (= 409 (:status r)) "sessao ja encerrada -> 409, mesmo com a votacao ainda 'aberta'")))
