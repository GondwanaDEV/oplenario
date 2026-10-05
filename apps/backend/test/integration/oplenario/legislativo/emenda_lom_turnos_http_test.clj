(ns oplenario.legislativo.emenda-lom-turnos-http-test
  "INTEGRACAO (PG real + a cadeia HTTP inteira de `rotas/montar`): a emenda a Lei Organica em DOIS TURNOS com
  intersticio de dez dias (CF art. 29; regra `emenda_lom` da mig 20261005000240, `turnos` 2 e `intersticio_dias` 10).
  Legislativo REAL (proposicao, votacao, autografo); identidade, cadastros e sessoes FAKE (9 vereadores: 2/3 = 6).

  O relogio da borda e' um atom: a abertura do 2o turno anda o relogio para depois do encerramento do 1o (que o banco
  carimba com o `now()` real). Prova: o 1o turno sozinho nao gera autografo (409); o 2o antes do intersticio e' 422
  com a data; no dia certo abre; os dois turnos aprovados liberam o autografo; um 3o turno e' 422; rejeitada no 2o
  turno a materia nao esta' aprovada e nao abre de novo; o projeto de lei comum segue aprovado com uma votacao."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.components.repositorio :as repo-cad]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.registro-fatos :as rf]
            [oplenario.rotas :as rotas]
            [oplenario.sessoes.components.repositorio :as repo-sessoes])
  (:import (java.time Duration Instant LocalDate ZoneId)
           (java.time.format DateTimeFormatter)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *svc* nil)

;; o relogio da borda: comeca no instante real (o banco carimba o encerramento com o `now()` real) e o teste o anda
(def ^:private agora (atom (Instant/now)))
(def ^:private relogio (reify tempo/Relogio (agora [_] @agora)))
(def ^:private fortaleza (ZoneId/of "America/Fortaleza"))

(def sec (random-uuid))
(def papeis {sec #{"secretario"}})
(def roster (vec (repeatedly 9 random-uuid)))

(defn- fake-identidade []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ id] {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis (get papeis id #{})})))

(defn- fake-cadastros []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cad/RepoCadastros
    (uf-e-municipio [_ _] {:uf "CE" :municipio-nome "Baturité"})
    (membros-da-casa [_ _ _] (count roster))
    (roster-da-casa [_ _ _] (mapv (fn [v] {:vereador-id v :estado-mandato "vigente"}) roster))))

(defn- fake-sessoes []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ ente-id id] {:id id :ente-id ente-id :estado "aberta" :transmite-publica true})))

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))
          reg (component/start (rf/registro-fatos {}))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)
                *svc* (-> (http/servico (config/carregar)
                                        (rotas/montar {:idp (idp-dev/idp-dev) :repo-identidade (fake-identidade)
                                                       :repo-legislativo (repo/->RepoLegislativoPg c (outbox/bus))
                                                       :repo-cadastros (fake-cadastros) :repo-sessoes (fake-sessoes)
                                                       :registro-fatos reg
                                                       :info-ente (constantly {:nome-oficial "Câmara"})
                                                       :relogio relogio})
                                        it/globais)
                          ph/create-server ::ph/service-fn)]
        (try (t) (finally (component/stop reg) (component/stop c)))))))

(defn- cab [ente quem]
  {"authorization" (str "Bearer " (json/write-value-as-string {:sub "u" :ente-id (str ente) :identidade-id (str quem)}))
   "Content-Type" "application/json"})

(defn- chamar [ente metodo url corpo]
  (let [r (pt/response-for *svc* metodo url :headers (cab ente sec) :body (json/write-value-as-string corpo))]
    {:status (:status r)
     :corpo (when (seq (:body r)) (json/read-value (:body r) json/keyword-keys-object-mapper))}))

(defn- protocolar! [ente tipo]
  (let [r (chamar ente :post "/legislativo/proposicoes"
                  {:tipo tipo :ano 2026 :ementa "Altera a Lei Orgânica quanto à composição da Mesa."
                   :texto "Art. 1º A Mesa Diretora passa a ter sete membros.\n\nArt. 2º Esta Emenda entra em vigor na data de sua publicação."})]
    (is (= 201 (:status r)) (pr-str r))
    (get-in r [:corpo :id])))

(defn- abrir [ente sessao pid]
  (chamar ente :post (str "/sessoes/" sessao "/votacoes")
          {:objeto-tipo "proposicao" :objeto-id pid :modalidade "nominal" :quorum-tipo "maioria_qualificada_2_3"}))

(defn- votar-e-encerrar!
  "Abre o turno e o encerra com `sim` votos sim (os demais do roster votam nao). 2/3 de 9 = 6."
  [ente sessao pid sim]
  (let [{:keys [status corpo]} (abrir ente sessao pid)
        vid (:id corpo)]
    (is (= 201 status) (pr-str corpo))
    (doseq [[v voto] (map vector roster (concat (repeat sim "sim") (repeat "nao")))]
      (is (= 201 (:status (chamar ente :post (str "/sessoes/" sessao "/votacoes/" vid "/votos")
                                  {:vereador-id (str v) :voto voto})))))
    (is (= 200 (:status (chamar ente :post (str "/sessoes/" sessao "/votacoes/" vid "/encerramento")
                                {:lock-version (:lock-version corpo)}))))
    vid))

(defn- autografo [ente pid]
  (chamar ente :post (str "/legislativo/proposicoes/" pid "/autografo") {}))

(defn- dia-do-encerramento [ente vid]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (LocalDate/ofInstant (:votacoes/agora (jdbc/execute-one! tx ["select atualizado_em as agora from legislativo.votacoes
                                                                  where ente_id = ? and id = ?::uuid" ente vid]))
                                  fortaleza))))

(defn- andar-o-relogio-para!
  "Poe o relogio da borda ao meio-dia (de Fortaleza) do dia `d`."
  [^LocalDate d]
  (reset! agora (.toInstant (.atZone (.atTime d 12 0) fortaleza))))

(deftest emenda-a-lom-so-e-aprovada-nos-dois-turnos-com-o-intersticio
  (reset! agora (Instant/now))
  (let [ente (random-uuid) sessao (random-uuid)
        pid (protocolar! ente "proposta_emenda_lom")
        t1 (votar-e-encerrar! ente sessao pid 7)
        desde (.plusDays ^LocalDate (dia-do-encerramento ente t1) 10)]
    (testing "aprovada so' no 1o turno: o autografo nao sai (409)"
      (is (= 409 (:status (autografo ente pid)))))
    (testing "o 2o turno no 9o dia: 422 com a data em palavras"
      (andar-o-relogio-para! (.minusDays desde 1))
      (let [{:keys [status corpo]} (abrir ente sessao pid)]
        (is (= 422 status))
        (is (= (str "O 2º turno desta emenda à Lei Orgânica só pode ser votado a partir de "
                    (.format desde (DateTimeFormatter/ofPattern "dd/MM/yyyy"))
                    ": são 10 dias depois do 1º turno (CF art. 29).")
               (:erro corpo)))
        (is (= (str desde) (:a-partir-de corpo)))
        (is (= "emenda_lom" (:regra corpo)))))
    (testing "o 2o turno tambem precisa dos 2/3 (a guarda da fatia 1 vale para todo turno)"
      (andar-o-relogio-para! desde)
      (is (= 422 (:status (chamar ente :post (str "/sessoes/" sessao "/votacoes")
                                  {:objeto-tipo "proposicao" :objeto-id pid :modalidade "nominal"
                                   :quorum-tipo "maioria_simples"})))))
    (testing "no 10o dia o 2o turno abre; aprovado, o autografo sai"
      (votar-e-encerrar! ente sessao pid 6)
      (let [r (autografo ente pid)]
        (is (= 201 (:status r)) (pr-str r))))
    (testing "aprovada nos dois turnos, nao ha' 3o turno"
      (andar-o-relogio-para! (.plusDays desde 30))
      (let [{:keys [status corpo]} (abrir ente sessao pid)]
        (is (= 422 status))
        (is (= "Esta emenda à Lei Orgânica já foi aprovada nos dois turnos (CF art. 29)." (:erro corpo)))))))

(deftest rejeitada-no-2o-turno-nao-esta-aprovada
  (reset! agora (Instant/now))
  (let [ente (random-uuid) sessao (random-uuid)
        pid (protocolar! ente "proposta_emenda_lom")
        t1 (votar-e-encerrar! ente sessao pid 9)
        desde (.plusDays ^LocalDate (dia-do-encerramento ente t1) 10)]
    (andar-o-relogio-para! desde)
    (votar-e-encerrar! ente sessao pid 5)
    (testing "5 de 9 no 2o turno (sem os 2/3): rejeitada — o 1o turno aprovado nao basta"
      (is (= 409 (:status (autografo ente pid)))))
    (testing "rejeitada, nao abre outro turno"
      (andar-o-relogio-para! (.plusDays desde 30))
      (let [{:keys [status corpo]} (abrir ente sessao pid)]
        (is (= 422 status))
        (is (str/starts-with? (:erro corpo) "Esta emenda à Lei Orgânica foi rejeitada no 2º turno"))))))

(deftest projeto-de-lei-comum-segue-aprovado-com-uma-votacao
  ;; instante cravado (precisao de segundo: o timestamptz guarda microssegundos e a comparacao abaixo e' exata)
  (reset! agora (.plus (Instant/ofEpochSecond (.getEpochSecond (Instant/now))) (Duration/ofDays 3)))
  (let [ente (random-uuid) sessao (random-uuid)
        pid (protocolar! ente "projeto_lei")
        r (chamar ente :post (str "/sessoes/" sessao "/votacoes")
                  {:objeto-tipo "proposicao" :objeto-id pid :modalidade "nominal" :quorum-tipo "maioria_simples"})
        vid (get-in r [:corpo :id])]
    (is (= 201 (:status r)))
    (doseq [v (take 3 roster)]
      (chamar ente :post (str "/sessoes/" sessao "/votacoes/" vid "/votos") {:vereador-id (str v) :voto "sim"}))
    (is (= 200 (:status (chamar ente :post (str "/sessoes/" sessao "/votacoes/" vid "/encerramento")
                                {:lock-version (get-in r [:corpo :lock-version])}))))
    (is (= 201 (:status (autografo ente pid))) "uma aprovacao basta para quem vota em um turno")
    (testing "o instante da abertura gravado e' o do relogio da borda"
      (is (= @agora (tenancy/com-tenant* *ds* ente
                      (fn [tx] (:votacoes/agora (jdbc/execute-one! tx ["select efetivado_em as agora from legislativo.votacoes
                                                               where ente_id = ? and id = ?::uuid" ente vid])))))))))
