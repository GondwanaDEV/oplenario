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
            [oplenario.cadastros.components.repositorio :as repo-cad]
            [oplenario.rotas :as rotas]
            [oplenario.sessoes.components.repositorio :as repo-sessoes]))

;; sec MEDIUM-1 FIX: o denominador do quorum (`base-membros`) e' computado SERVER-SIDE no encerramento via o
;; seam `membros-da-casa` (relacao de cadastros injetada pelo host), nunca mais do corpo. Este dynamic deixa
;; cada teste fixar a composicao REAL que o fake repo-cadastros devolve — provando que o resultado usa ESTE
;; valor, e nao o que o cliente mandaria.
(def ^:dynamic *membros-da-casa* 11)

(defn- fake-repo-cadastros
  "RepoCadastros fake (parcial) — so `membros-da-casa`, o unico metodo que a vertical de votacao alcanca
   depois do fix (o host o injeta no encerramento). Devolve `*membros-da-casa*` (fuso/data ignorados: o
   valor e' o que o teste fixou)."
  []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cad/RepoCadastros
    (membros-da-casa [_ _ente-id _data] *membros-da-casa*)))

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

(defn- sessao-nao-publica
  "A MESMA sessao, mas `transmite-publica false` (carry telao, Daouda 12/09/2026) — `service-fn*` injeta o
  `pode-ver-votacao-aberta?` REAL (`sessoes.logic/pode-ver-quorum-da-sessao?`) via `rotas/montar`, entao os
  testes da fronteira abaixo exercitam a wiring de producao completa, nao um stub local. E' o CAMPO, nao o
  `tipo-sessao`, que a politica le' (mesma fixture minima de `sessao-canonica`/`sessao-encerrada`)."
  [ente-id id]
  (assoc (sessao-canonica ente-id id) :transmite-publica false))

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
      ;; ECOA o `base-membros` recebido em `m` (nao um literal): o controller o resolve SERVER-SIDE e assoc
      ;; em `m`, entao a resposta prova QUE valor o servidor usou — se viesse do corpo, este eco denunciaria.
      {:id (:id m) :estado "encerrada" :resultado "aprovada"
       :sim 6 :nao 3 :abstencao 1 :base-membros (:base-membros m)})))

(defn- fake-repo-identidade
  "`tipo-vinculo` (default \"servidor\") existe para a fronteira da sessao NAO-publica (carry telao, Daouda
  12/09/2026): um ator com vinculo 'cidadao' e papeis vazios e' o publico REAL que `pode-ver-votacao-aberta?`
  (= sessoes.logic/pode-ver-quorum-da-sessao?) precisa barrar — nao um servidor sem papel, que nao existe
  como perfil real desta rota."
  ([papeis] (fake-repo-identidade papeis "servidor"))
  ([papeis tipo-vinculo]
   #_{:clj-kondo/ignore [:missing-protocol-method]}
   (reify repo-id/RepoIdentidade
     (snapshot-ator [_ _ente-id _identidade-id]
       {:vinculo-ativo {:id (random-uuid) :tipo tipo-vinculo} :papeis papeis}))))

(defn- service-fn*
  ([papeis repo-s repo-l] (service-fn* papeis "servidor" repo-s repo-l))
  ([papeis tipo-vinculo repo-s repo-l]
   (-> (http/servico (config/carregar)
                     (rotas/montar {:idp (idp-dev/idp-dev)
                                    :repo-identidade (fake-repo-identidade papeis tipo-vinculo)
                                    :repo-sessoes repo-s
                                    :repo-legislativo repo-l
                                    ;; sec MEDIUM-1: o encerramento resolve base-membros via este repo
                                    :repo-cadastros (fake-repo-cadastros)})
                     it/globais)
       ph/create-server ::ph/service-fn)))

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
        corpo (json/write-value-as-string {:lock-version 0})
        r (pt/response-for (service-fn* #{"secretario"} repo-s repo-l)
                           :post (str "/sessoes/" sid "/votacoes/" vid "/encerramento")
                           :headers (com-json (token ente (random-uuid))) :body corpo)
        body (ler-json r)]
    (is (= 200 (:status r)) "encerrar votacao da mesma sessao -> 200")
    (is (= "encerrada" (:estado body)))
    (is (= "aprovada" (:resultado body)))
    (is (= 6 (:total-sim body)) "totais projetados (sim->total-sim)")
    (is (= 11 (:base-membros body)) "base-membros = composicao real da Casa (*membros-da-casa*), resolvida server-side")
    (is (some #{:encerrar} @chamadas) "chamou encerrar-votacao! do Repo")))

(deftest encerrar-votacao-base-membros-no-corpo-400
  ;; sec MEDIUM-1 FECHADO: `base-membros` NAO existe mais no wire/in.EncerrarVotacao (`:closed true`). Um
  ;; secretario comprometido que TENTE forjar o denominador (ex.: base-membros=1 aprova tudo) e' barrado na
  ;; BORDA com 400, antes de qualquer apuracao — a porta esta fechada por construcao, nao por vigilancia.
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo (fn [_ _] (votacao-canonica ente vid sid "nominal")) (atom []))
        corpo (json/write-value-as-string {:lock-version 0 :base-membros 1})
        r (pt/response-for (service-fn* #{"secretario"} repo-s repo-l)
                           :post (str "/sessoes/" sid "/votacoes/" vid "/encerramento")
                           :headers (com-json (token ente (random-uuid))) :body corpo)]
    (is (= 400 (:status r)) "mandar base-membros no corpo -> 400 (campo nao existe no contrato, anti-forja)")))

(deftest encerrar-votacao-usa-composicao-do-servidor
  ;; sec MEDIUM-1 FECHADO (o outro lado da prova): mesmo um corpo VALIDO nao move o denominador — ele vem
  ;; SEMPRE de `membros-da-casa` (aqui fixado em 7). O fake repo ecoa o base-membros que RECEBEU; se o
  ;; controller tivesse deixado o cliente influir, o eco denunciaria. 7, nao o que o cliente quisesse.
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        chamadas (atom [])
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo (fn [_ _] (votacao-canonica ente vid sid "nominal")) chamadas)
        corpo (json/write-value-as-string {:lock-version 0})
        r (binding [*membros-da-casa* 7]
            (pt/response-for (service-fn* #{"secretario"} repo-s repo-l)
                             :post (str "/sessoes/" sid "/votacoes/" vid "/encerramento")
                             :headers (com-json (token ente (random-uuid))) :body corpo))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= 7 (:base-membros body)) "o denominador e' a composicao real da Casa (7), resolvida server-side")))

(deftest encerrar-votacao-de-outra-sessao-404
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid) outra (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo (fn [_ _] (votacao-canonica ente vid outra "nominal")) (atom []))
        corpo (json/write-value-as-string {:lock-version 0})
        r (pt/response-for (service-fn* #{"secretario"} repo-s repo-l)
                           :post (str "/sessoes/" sid "/votacoes/" vid "/encerramento")
                           :headers (com-json (token ente (random-uuid))) :body corpo)]
    (is (= 404 (:status r)) "encerrar votacao de outra sessao -> 404 (amarra votacao<->sessao)")))

(deftest encerrar-votacao-sem-papel-403
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo (fn [_ _] (votacao-canonica ente vid sid "nominal")) (atom []))
        corpo (json/write-value-as-string {:lock-version 0})
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
        corpo (json/write-value-as-string {:lock-version 0})
        r (pt/response-for (service-fn* #{"secretario"} repo-s repo-l)
                           :post (str "/sessoes/" sid "/votacoes/" vid "/encerramento")
                           :headers (com-json (token ente (random-uuid))) :body corpo)]
    (is (= 409 (:status r)) "reencerrar votacao terminal -> 409 (conflito de estado, nao pedido invalido)")))

(deftest encerrar-votacao-id-malformado-400
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo (fn [_ _] nil) (atom []))
        corpo (json/write-value-as-string {:lock-version 0})
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
        corpo (json/write-value-as-string {:lock-version 0})
        r (pt/response-for (service-fn* #{"secretario"} repo-s repo-l)
                           :post (str "/sessoes/" sid "/votacoes/" vid "/encerramento")
                           :headers (com-json (token ente (random-uuid))) :body corpo)]
    (is (= 409 (:status r)) "sessao ja encerrada -> 409, mesmo com a votacao ainda 'aberta'")))

;; ---------- GET /sessoes/:id/votacao-aberta — recuperacao de estado (fatia 'demo-tres-consertos' #2b) ----------
;; Achado ao vivo (Daouda, 12/09/2026): a Casa recem-semeada tinha uma votacao 'aberta' no banco, mas o
;; canal Valkey (retencao MINID ~5min) estava vazio — o vereador via "Nenhuma votacao aberta" com uma
;; votacao de verdade aberta. TODO teste aqui embaixo é o cenario REAL: um cliente HTTP que conecta SEM
;; nenhum evento no stream (esta suite não simula SSE nenhum) e descobre a votacao ASSIM MESMO.

(defn- fake-repo-legislativo-votacao-aberta
  "RepoLegislativo fake pra' `votacao-aberta`: `votacao-aberta-da-sessao` (a votacao 'aberta' da sessao, ou
  nil) + `buscar-proposicao` + `votos-da-votacao`/`contar-votos-secretos-da-votacao`."
  [votacao-aberta proposicao votos-nominais votos-secretos-total]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-leg/RepoLegislativo
    (votacao-aberta-da-sessao [_ _ente-id _sessao-id] votacao-aberta)
    (buscar-proposicao [_ _ente-id _id] proposicao)
    (votos-da-votacao [_ _ente-id _votacao-id] votos-nominais)
    (contar-votos-secretos-da-votacao [_ _ente-id _votacao-id] votos-secretos-total)))

(deftest votacao-aberta-nominal-200-descobre-sem-nenhum-evento-de-stream
  ;; O CENARIO REAL do defeito: nenhum SSE, nenhum evento — só a leitura HTTP fria.
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid) pid (random-uuid) vd1 (random-uuid) vd2 (random-uuid)
        votacao (assoc (votacao-canonica ente vid sid "nominal") :objeto-tipo "proposicao" :objeto-id pid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo-votacao-aberta
                votacao (proposicao-canonica pid)
                [{:vereador-id vd1 :voto "sim"} {:vereador-id vd2 :voto "nao"}] nil)
        r (pt/response-for (service-fn* #{"vereador"} repo-s repo-l)
                           :get (str "/sessoes/" sid "/votacao-aberta")
                           :headers (com-json (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)) "descobre a votacao aberta mesmo sem NENHUM evento SSE visto")
    (is (= (str vid) (:votacao-id body)))
    (is (= "nominal" (:modalidade body)))
    (is (= "proposicao" (:objeto-tipo body)))
    (is (= (str pid) (:objeto-id body)) "objeto-id acompanha a votacao (mesmo campo que o evento SSE ao vivo carregaria)")
    (is (= 42 (:sequencial (:proposicao body))) "a MESMA resolucao de objeto da Fatia 2 (uma ida, nao duas)")
    (is (= #{{:vereador-id (str vd1) :voto "sim"} {:vereador-id (str vd2) :voto "nao"}} (set (:votos body)))
        "os votos ja registrados chegam — o cliente reconstroi o placar sem esperar novos eventos")
    (is (nil? (:votos-registrados body)) "ramo nominal nunca carrega o campo do ramo secreta/simbolica")))

(deftest votacao-aberta-secreta-200-so-contagem-nunca-vereador-id
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid) pid (random-uuid)
        votacao (assoc (votacao-canonica ente vid sid "secreta") :objeto-tipo "proposicao" :objeto-id pid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo-votacao-aberta votacao (proposicao-canonica pid) nil 7)
        r (pt/response-for (service-fn* #{"vereador"} repo-s repo-l)
                           :get (str "/sessoes/" sid "/votacao-aberta")
                           :headers (com-json (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= 7 (:votos-registrados body)) "so' o tick anonimo — o MESMO que voto.registrado secreto ja' expoe ao vivo")
    (is (not (contains? body :votos)) "sigilo §22.6: o ramo secreta NUNCA carrega a lista individual")
    (is (not (re-find #"vereador" (:body r))) "nem a CHAVE 'vereador-id' aparece no corpo de uma votacao secreta")))

(deftest votacao-aberta-simbolica-200-so-contagem
  ;; 'simbolica' nunca registra voto individual (controllers/registrar-voto) — mesma forma da secreta,
  ;; contagem sempre 0 na pratica, mas o CONTRATO trata os dois ramos identicamente (nao um terceiro molde).
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid) pid (random-uuid)
        votacao (assoc (votacao-canonica ente vid sid "simbolica") :objeto-tipo "proposicao" :objeto-id pid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo-votacao-aberta votacao (proposicao-canonica pid) nil 0)
        r (pt/response-for (service-fn* #{"vereador"} repo-s repo-l)
                           :get (str "/sessoes/" sid "/votacao-aberta")
                           :headers (com-json (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= "simbolica" (:modalidade body)))
    (is (= 0 (:votos-registrados body)))
    (is (not (contains? body :votos)))))

(deftest votacao-aberta-emenda-sem-proposicao-honesto
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        votacao (assoc (votacao-canonica ente vid sid "nominal") :objeto-tipo "emenda" :objeto-id (random-uuid))
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo-votacao-aberta votacao nil [] nil)
        r (pt/response-for (service-fn* #{"vereador"} repo-s repo-l)
                           :get (str "/sessoes/" sid "/votacao-aberta")
                           :headers (com-json (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= "emenda" (:objeto-tipo body)))
    (is (nil? (:proposicao body)) "mesma honestidade da Fatia 2 — sem titulo inventado")))

(deftest votacao-aberta-nenhuma-aberta-404-estado-legitimo
  ;; 'nenhuma votacao aberta' segue certo QUANDO E' VERDADE — este e' o caso em que o servidor CONCORDA
  ;; com a tela calma (ver o teste irmao acima, onde o servidor DISCORDA e a rota corrige).
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo-votacao-aberta nil nil nil nil)
        r (pt/response-for (service-fn* #{"vereador"} repo-s repo-l)
                           :get (str "/sessoes/" sid "/votacao-aberta")
                           :headers (com-json (token ente (random-uuid))))]
    (is (= 404 (:status r)) "sem votacao aberta -> 404, estado legitimo (nao e' erro)")))

;; ---------- B1b: a FRONTEIRA DA SESSAO NAO-PUBLICA (carry telao, Daouda 12/09/2026) ----------
;; Ate' aqui a borda exigia papel 'vereador' ESTRITO — o telao da Mesa ('secretario') tomava 403 na unica
;; rota que recupera a votacao aberta apos a retencao MINID de ~5min do canal. Tirar o papel da borda SEM
;; por a politica na camada fina reabriria a mesma porta dos fundos que a docstring de
;; `pode-ver-quorum-da-sessao?` registra ter acontecido uma vez em `/quorum`: QUALQUER vinculo ativo da
;; Casa — inclusive cidadao, sem papel nenhum — leria a votacao em curso de uma sessao SECRETA.
;;
;; Revisao do Daouda (12/09/2026): a politica final tem TRES clausulas, nao duas — mesma Casa E
;; (transmissao publica OU 'secretario' OU 'vereador'). O vereador entra pelo MESMO argumento da docstring
;; de `pode-ver-quorum-da-sessao?` levado ate' o fim: o gate e' de PUBLICO (quem so' assiste), nao de
;; sessao; numa sessao secreta o vereador VOTA (`/meu-voto` e' gated 'vereador') — quem registra o voto
;; tem direito de saber que ela esta' aberta. A composicao mora em `pode-ver-votacao-aberta?` (rotas.clj):
;; `(or (pode-ver-quorum-da-sessao? a s) (and (pode-ver-sessao? a s) (papel vereador)))` — a disjuncao de
;; papel entra escopada DENTRO de `pode-ver-sessao?` (mesma Casa), nunca por fora; um `(or (papel vereador)
;; ...)` isolado deixaria passar um vereador de OUTRA Casa. Os testes abaixo cravam a politica INTEIRA.

(deftest votacao-aberta-sessao-nao-publica-nega-cidadao-sem-papel-403
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-nao-publica ente sid)))
        repo-l (fake-repo-legislativo-votacao-aberta nil nil nil nil)
        r (pt/response-for (service-fn* #{} "cidadao" repo-s repo-l)
                           :get (str "/sessoes/" sid "/votacao-aberta")
                           :headers (com-json (token ente (random-uuid))))]
    (is (= 403 (:status r))
        "vinculo 'cidadao' sem papel nenhum, mesma Casa, sessao NAO publica -> 403 (a rota nao pode virar a porta dos fundos)")))

(deftest votacao-aberta-sessao-nao-publica-nega-outra-casa-403
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-nao-publica (random-uuid) id)))
        repo-l (fake-repo-legislativo-votacao-aberta nil nil nil nil)
        r (pt/response-for (service-fn* #{"secretario"} repo-s repo-l)
                           :get (str "/sessoes/" sid "/votacao-aberta")
                           :headers (com-json (token ente (random-uuid))))]
    (is (= 403 (:status r))
        "sessao carregada mas de ente alheio -> nega ANTES de chegar na clausula de publico/secretario")))

(deftest votacao-aberta-sessao-publica-segue-aberta-a-quem-nao-tem-papel-200
  ;; A prova de que o aperto acima nao matou a fatia: um ator SEM papel nenhum (o 'operador de som' da
  ;; docstring de `quorum-handler`), numa sessao com transmissao publica, continua recuperando a votacao.
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid) pid (random-uuid)
        votacao (assoc (votacao-canonica ente vid sid "nominal") :objeto-tipo "proposicao" :objeto-id pid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo-votacao-aberta votacao (proposicao-canonica pid) [] nil)
        r (pt/response-for (service-fn* #{} repo-s repo-l)
                           :get (str "/sessoes/" sid "/votacao-aberta")
                           :headers (com-json (token ente (random-uuid))))]
    (is (= 200 (:status r)) "sessao com transmissao publica + ator sem papel -> 200, a razao de existir da fatia")))

(deftest votacao-aberta-sessao-nao-publica-continua-visivel-ao-secretario-200
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid) pid (random-uuid)
        votacao (assoc (votacao-canonica ente vid sid "secreta") :objeto-tipo "proposicao" :objeto-id pid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-nao-publica ente sid)))
        repo-l (fake-repo-legislativo-votacao-aberta votacao (proposicao-canonica pid) nil 3)
        r (pt/response-for (service-fn* #{"secretario"} repo-s repo-l)
                           :get (str "/sessoes/" sid "/votacao-aberta")
                           :headers (com-json (token ente (random-uuid))))]
    (is (= 200 (:status r)) "o telao ('secretario') e' exatamente quem esta fatia existe para destravar")))

(deftest votacao-aberta-sessao-nao-publica-continua-visivel-ao-vereador-200
  ;; Revisao do Daouda (12/09/2026), decisao tomada: o vereador (sem 'secretario') CONTINUA recuperando a
  ;; votacao numa sessao NAO publica — nao e' concessao, e' a mesma logica que ja' deixa 'secretario'
  ;; passar aqui, levada ate' o fim. Numa sessao secreta e' ELE quem vota (`/meu-voto` e' gated
  ;; 'vereador'); negar-lhe esta leitura nao protege sigilo nenhum, so' devolveria "recarregou a pagina e
  ;; nao vota" no cenario de maior consequencia. Esta prova PASSOU a ser positiva — ela e' a prova de que
  ;; a clausula 'vereador' da composicao (rotas.clj) pegou; a mutacao abaixo (nao permanente) mostra o
  ;; caminho inverso.
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid) pid (random-uuid)
        votacao (assoc (votacao-canonica ente vid sid "secreta") :objeto-tipo "proposicao" :objeto-id pid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-nao-publica ente sid)))
        repo-l (fake-repo-legislativo-votacao-aberta votacao (proposicao-canonica pid) nil 5)
        r (pt/response-for (service-fn* #{"vereador"} repo-s repo-l)
                           :get (str "/sessoes/" sid "/votacao-aberta")
                           :headers (com-json (token ente (random-uuid))))]
    (is (= 200 (:status r))
        "vereador sem papel 'secretario', sessao NAO publica -> 200 (ele vota ali; negar-lhe a leitura nao protege sigilo)")))

(deftest votacao-aberta-sessao-nao-publica-nega-vereador-de-outra-casa-403
  ;; A prova de FORMA (Daouda, 12/09/2026): a clausula 'vereador' tem de estar ESCOPADA a mesma Casa, nao
  ;; solta na disjuncao. Um vereador de OUTRA Casa, com o MESMO papel que acima passa, tem de continuar
  ;; negado — senao a composicao teria a forma errada `(or (papel vereador) (pode-ver-quorum-da-sessao? a
  ;; s))`, que nao escopa o papel ao tenant.
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-nao-publica (random-uuid) id)))
        repo-l (fake-repo-legislativo-votacao-aberta nil nil nil nil)
        r (pt/response-for (service-fn* #{"vereador"} repo-s repo-l)
                           :get (str "/sessoes/" sid "/votacao-aberta")
                           :headers (com-json (token ente (random-uuid))))]
    (is (= 403 (:status r))
        "vereador de ente ALHEIO, sessao NAO publica -> 403 (a clausula 'vereador' nao vaza pra fora da mesma Casa)")))

(deftest votacao-aberta-sessao-inexistente-404
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil))
        repo-l (fake-repo-legislativo-votacao-aberta nil nil nil nil)
        r (pt/response-for (service-fn* #{"vereador"} repo-s repo-l)
                           :get (str "/sessoes/" (random-uuid) "/votacao-aberta")
                           :headers (com-json (token ente (random-uuid))))]
    (is (= 404 (:status r)))))

(deftest votacao-aberta-sessao-encerrada-409
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-encerrada ente sid)))
        repo-l (fake-repo-legislativo-votacao-aberta nil nil nil nil)
        r (pt/response-for (service-fn* #{"vereador"} repo-s repo-l)
                           :get (str "/sessoes/" sid "/votacao-aberta")
                           :headers (com-json (token ente (random-uuid))))]
    (is (= 409 (:status r)) "sessao ja encerrada -> 409, mesmo gate das outras rotas de votacao")))

(deftest votacao-aberta-sessao-id-malformado-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [e i] (sessao-canonica e i)))
        repo-l (fake-repo-legislativo-votacao-aberta nil nil nil nil)
        r (pt/response-for (service-fn* #{"vereador"} repo-s repo-l)
                           :get "/sessoes/nao-e-uuid/votacao-aberta"
                           :headers (com-json (token ente (random-uuid))))]
    (is (= 400 (:status r)) "path-param :id malformado -> 400, nunca 500")))

(deftest votacao-aberta-sem-token-401
  (let [ente (random-uuid) sid (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] (sessao-canonica ente sid)))
        repo-l (fake-repo-legislativo-votacao-aberta nil nil nil nil)
        r (pt/response-for (service-fn* #{"vereador"} repo-s repo-l)
                           :get (str "/sessoes/" sid "/votacao-aberta"))]
    (is (= 401 (:status r)))))
