(ns oplenario.transparencia.http-perfil-test
  "INTEGRACAO (PG real + borda Pedestal) — Onda E fatia 2, Task 4: a rota PUBLICA
  GET /portal/casa/:ente/vereadores/:vereador_id.

  E' a rota de MAIOR risco da fatia: SEM auth (qualquer erro de escopo vaza dado entre Casas), com SIGILO DE
  VOTO em jogo (so' o voto NOMINAL ja' projetado pode sair) e FUNDINDO dois modulos — a identidade vem de
  `cadastros` por INVERSAO DE DEPENDENCIA (seam `ficha-vereador-publica` injetado pelo host, §22.10:
  `transparencia` nunca importa `cadastros`), os numeros vem do read-model proprio.

  Harness: `rotas/montar` de verdade (mesma silhueta de info_ente_http_in_test) com o Repo de transparencia
  REAL contra Postgres (a leitura composta da Task 3 precisa de dado projetado) e o seam de identidade FAKE,
  escopado por (ente, vereador) — e' exatamente o contrato do seam real (`Repo/ficha-vereador` filtra
  `ente_id` + RLS). A semeadura e' sempre pelo caminho de PROJECAO (Repo de legislativo -> outbox -> relay ->
  consumer do portal), nunca INSERT direto no read-model — mesmo racional de portal_test/perfil_test."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.cadastros.relacoes.cadastro :as rel-cad]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.relacoes.identidade :as rel-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.repositorio :as legislativo-repo]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.registro-fatos :as rf]
            [oplenario.rotas :as rotas]
            [oplenario.transparencia.components.repositorio :as transparencia-repo]
            [oplenario.transparencia.diplomat.consumers :as consumers]))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo-legislativo* nil)
(def ^:dynamic *repo-transparencia* nil)

(use-fixtures :once
  (fn [t]
    (let [c   (component/start (datasource/datasource (config/carregar)))
          reg (component/start (rf/registro-fatos (merge rel-cad/relacoes rel-id/relacoes)))
          bus (outbox/bus)]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)
                *repo-legislativo* (legislativo-repo/->RepoLegislativoPg c bus)
                *repo-transparencia* (transparencia-repo/->RepoTransparenciaPg c)]
        (try (t) (finally (component/stop reg) (component/stop c)))))))

;; ---------- harness de borda ----------

(defn- ficha-fixture
  "A ficha que o seam real (`cadastros/Repo/ficha-vereador`) devolve: {:vereador :mandato :legislatura
  :comissoes}. Um cargo de Mesa entra como comissao tipo 'mesa' (a UNICA fonte de cargo-mesa — mesmo
  contrato de cadastros/adapters/out/vereador)."
  [vereador-id]
  {:vereador   {:id vereador-id :nome "Helena Pastore de Andrade" :nome-parlamentar "Helena Past"
                :identidade-id (random-uuid) :ente-id (random-uuid)}
   :mandato    {:partido "PT" :estado "ativo" :natureza "titular"}
   :legislatura {:numero 19 :ano-inicio 2025 :ano-fim 2028}
   :comissoes  [{:nome "Mesa Diretora" :tipo "mesa" :cargo "1o Secretario"}
                {:nome "Comissao de Financas" :tipo "permanente" :cargo "membro"}]})

(defn- seam-escopado
  "Seam FAKE de identidade, escopado por (ente, vereador) como o real. `cadastrados` = mapa {[ente ver] ficha}."
  [cadastrados]
  (fn [ente-id vereador-id] (get cadastrados [ente-id vereador-id])))

(defn- service-fn [ficha-vereador-publica]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade nil
                                   :repo-transparencia *repo-transparencia*
                                   :ficha-vereador-publica ficha-vereador-publica})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(defn- GET
  "GET publico (SEM header Authorization) no perfil de `vereador` da Casa `ente`."
  [seam ente vereador]
  (pt/response-for (service-fn seam) :get (str "/portal/casa/" ente "/vereadores/" vereador)))

;; ---------- semeadura (caminho de projecao real) ----------

(defn- drenar! [] (outbox/drenar! *ds* (consumers/registrar {})))

(defn- protocolar! [ente ementa extra]
  (:id (legislativo-repo/protocolar! *repo-legislativo* ente
         (merge {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                 :municipio-nome "Fortaleza" :ementa ementa}
                extra))))

(defn- votar!
  "Voto NOMINAL pelo caminho REAL (abrir-votacao! + registrar-voto! -> `voto.registrado`)."
  [ente pid vereador voto]
  (let [{vid :id} (legislativo-repo/abrir-votacao! *repo-legislativo* ente
                    {:id (random-uuid) :objeto-tipo "proposicao" :objeto-id pid :sessao-id (random-uuid)
                     :modalidade "nominal" :quorum-tipo "maioria_simples"})]
    (legislativo-repo/registrar-voto! *repo-legislativo* ente
      {:id (random-uuid) :votacao-id vid :vereador-id vereador :voto voto})
    vid))

(defn- projetar-lote!
  "Semeia `n` materias de autoria do vereador numa UNICA tx (o mesmo `projetar-evento!` do consumer) — unico
  jeito de exceder o teto de 200 de `listar-por-autor` sem 205 round-trips pelo relay."
  [ente vereador n]
  (tenancy/com-tenant* *ds* ente
    (fn [tx]
      (doseq [i (range n)]
        (transparencia-repo/projetar-evento! tx
          {:tipo "proposicao.protocolada" :ente-id ente
           :payload {:proposicao-id (str (random-uuid)) :tipo "projeto_lei" :ano 2026 :sequencial (inc i)
                     :urn-lex (str "urn:lex:lote:" ente ":" i) :ementa (str "Materia de lote " i)
                     :estado "protocolada" :autor-tipo "vereador" :autor-id (str vereador)}})))))

;; ---------- 1. o caso feliz: 200 sem login, identidade + numeros fundidos ----------

(deftest perfil-publico-200-sem-login
  (testing "GET /portal/casa/:ente/vereadores/:id devolve 200 SEM token, fundindo identidade e atuacao"
    (let [ente     (random-uuid)
          vereador (random-uuid)
          pid      (protocolar! ente "Hortas comunitarias"
                                {:autor-tipo "vereador" :autor-id vereador :autor-texto "Helena Past"})]
      (drenar!)
      (let [r    (GET (seam-escopado {[ente vereador] (ficha-fixture vereador)}) ente vereador)
            body (ler-json r)]
        (is (= 200 (:status r)) "rota do portal PUBLICO — sem Authorization, nunca 401")
        (is (not= 401 (:status r)))
        (is (= (str vereador) (:vereador-id body)))
        (is (= "Helena Past" (:nome-parlamentar body)))
        (is (= "Helena Pastore de Andrade" (:nome-civil body)))
        (is (= "1o Secretario" (:cargo-mesa body)) "cargo-mesa deriva da comissao tipo 'mesa'")
        (is (= ["Mesa Diretora" "Comissao de Financas"] (:comissoes body)))
        (is (vector? (:materias body)))
        (is (= [(str pid)] (mapv :proposicao-id (:materias body))))
        (is (= 1 (:materias-total body)))
        (is (= 0 (:normas-de-autoria body)))
        (is (= {:sessoes-presente 0 :sessoes-com-chamada 0} (:presenca body)))
        (is (string? (:acervo-com-elo-de-autoria-desde body))
            "a data do elo de autoria acompanha a resposta (a UI declara o acervo legado sem elo)")))))

(deftest perfil-nao-vaza-identificador-interno
  (testing "o wire nao carrega ente-id/identidade-id/mandato-id nem chave alguma fora do contrato"
    (let [ente     (random-uuid)
          vereador (random-uuid)
          r        (GET (seam-escopado {[ente vereador] (ficha-fixture vereador)}) ente vereador)
          body     (ler-json r)]
      (is (= 200 (:status r)))
      (is (= #{:vereador-id :nome-parlamentar :nome-civil :legislatura :cargo-mesa :comissoes
               :materias :materias-total :normas-de-autoria :votos :presenca
               :acervo-com-elo-de-autoria-desde}
             (set (keys body)))
          "conjunto EXATO de chaves — nada a mais (o :closed do Malli e' o guarda em producao)")
      (is (not (contains? body :ente-id)))
      (is (not (contains? body :identidade-id))))))

;; ---------- 2. fail-closed: 404 antes de qualquer leitura de perfil ----------

(deftest perfil-de-vereador-inexistente-404
  (testing "vereador inexistente -> 404, NUNCA 200 com perfil vazio (insinuaria parlamentar sem atuacao)"
    (let [r (GET (seam-escopado {}) (random-uuid) (random-uuid))]
      (is (= 404 (:status r)))
      (is (nil? (:materias (ler-json r))) "nao ha corpo de perfil no 404"))))

(deftest perfil-nao-vaza-outro-tenant
  (testing "vereador cadastrado em OUTRA Casa -> 404 na Casa consultada, e nenhum numero dele sai"
    (let [ente-a   (random-uuid)
          ente-b   (random-uuid)
          vereador (random-uuid)
          ;; o vereador EXISTE (na Casa B) e TEM atuacao projetada na Casa A com o mesmo uuid: se o guard
          ;; fosse escopado errado (ou consultasse o perfil antes), a Casa A devolveria 200 com os numeros.
          pid      (protocolar! ente-a "Materia do ente A"
                                {:autor-tipo "vereador" :autor-id vereador :autor-texto "Helena Past"})
          seam     (seam-escopado {[ente-b vereador] (ficha-fixture vereador)})]
      (drenar!)
      (is (some? pid))
      (let [r (GET seam ente-a vereador)]
        (is (= 404 (:status r)) "o seam e' consultado com o ente do PATH — cadastro em outra Casa nao serve")
        (is (nil? (:materias (ler-json r))) "nenhum numero da Casa A vaza sob a identidade da Casa B"))
      (is (= 200 (:status (GET seam ente-b vereador))) "na propria Casa, o mesmo vereador responde 200"))))

;; ---------- 3. borda: coercao fail-closed do path-param ----------

(deftest vereador-id-malformado-400
  (testing "vereador_id que nao coage a UUID -> 400 (nunca 500, nunca 404 mentiroso)"
    (let [r (pt/response-for (service-fn (seam-escopado {})) :get
                             (str "/portal/casa/" (random-uuid) "/vereadores/nao-e-um-uuid"))]
      (is (= 400 (:status r))))))

;; ---------- 4. sigilo/fidelidade do voto ----------

(deftest perfil-expoe-voto-nominal-ja-projetado-e-nada-alem
  (testing "a secao 'como votou' publica o voto NOMINAL projetado, com o rotulo da materia, e so' isso"
    (let [ente     (random-uuid)
          vereador (random-uuid)
          pid      (protocolar! ente "Hortas comunitarias"
                                {:autor-tipo "vereador" :autor-id vereador :autor-texto "Helena Past"})
          votacao  (votar! ente pid vereador "nao")]
      (drenar!)
      (let [body (ler-json (GET (seam-escopado {[ente vereador] (ficha-fixture vereador)}) ente vereador))
            v    (first (:votos body))]
        (is (= 1 (count (:votos body))))
        (is (= (str votacao) (:votacao-id v)))
        (is (= "nao" (:voto v)) "o voto sai como projetado — nao ha reescrita na borda")
        (is (= "projeto_lei 1/2026" (:materia-rotulo v)) "rotulo legivel montado do tipo/sequencial/ano")
        (is (= "Hortas comunitarias" (:materia-ementa v)))
        (is (string? (:ocorrido-em v)))
        (is (= #{:votacao-id :voto :ocorrido-em :materia-rotulo :materia-ementa} (set (keys v)))
            "o item de voto nao carrega vereador-id/ente-id/proposicao-id crus")))))

;; ---------- 5. truncamento declarado ----------

(deftest materias-total-declara-o-truncamento-na-borda
  (testing "acima do teto a lista trunca em 200 e :materias-total sai na resposta com o universo inteiro"
    (let [ente     (random-uuid)
          vereador (random-uuid)]
      (projetar-lote! ente vereador 205)
      (let [body (ler-json (GET (seam-escopado {[ente vereador] (ficha-fixture vereador)}) ente vereador))]
        (is (= 200 (count (:materias body))) "a lista para no teto server-side")
        (is (= 205 (:materias-total body))
            "sem esta chave o :closed do wire derrubaria a resposta e a UI fingiria acervo completo")))))
