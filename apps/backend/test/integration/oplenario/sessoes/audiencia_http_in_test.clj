(ns oplenario.sessoes.audiencia-http-in-test
  "INTEGRACAO (PG real + a cadeia HTTP inteira de `rotas/montar`): ADR-0021 Parte A — a AUDIENCIA PUBLICA. Sessoes e
  legislativo REAIS; identidade e cadastros FAKE (papeis, vinculo de cidadao, nome da identidade, comissoes vigentes).
  Prova: agendar com o bloco (comissao vigente, metas fiscais com referencia), a Mesa (ver, ajustar, inscrever no dia,
  chamar uma por vez, encerrar com o tempo, ausente), a cidada pelo gov.br (o nome vem da identidade, ciente da
  publicidade, uma inscricao por audiencia, inscricoes fechadas, desistencia so' do dono), o portal (so' transmissao
  publica; quem falou so' depois de encerrada; a ata publicada), a RLS entre Casas, a votacao recusada em sessao que
  nao delibera, o quorum que diz `exige-quorum`, e a audiencia fora da 'sessao anterior' da leitura da ata."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.cadastros.components.repositorio :as repo-cadastros-comp]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [next.jdbc :as jdbc]
            [oplenario.migracao :as migracao]
            [oplenario.rotas :as rotas]
            [oplenario.sessoes.components.repositorio :as repo-s])
  (:import (java.time Instant)))

(def ^:dynamic *leg* nil)
(def ^:dynamic *ses* nil)
(def ^:dynamic *svc* nil)

(def agora (Instant/parse "2026-10-06T12:00:00Z"))
(def inicio (Instant/parse "2026-10-13T12:00:00Z"))

(def sec (random-uuid))
(def ver (random-uuid))
(def cid (random-uuid))
(def cid2 (random-uuid))
(def comissao (random-uuid))
(def comissao-extinta (random-uuid))

(def perfis
  {sec {:tipo "servidor" :papeis #{"secretario"} :nome "Marina Freire"}
   ver {:tipo "servidor" :papeis #{"vereador"} :nome "Vereadora Ana"}
   cid {:tipo "cidadao" :papeis #{} :nome "Roberta Costa Aguiar"}
   cid2 {:tipo "cidadao" :papeis #{} :nome "Joao Pereira"}})

(def casas (atom #{}))

(defn- fake-identidade []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ id] (when-let [p (perfis id)] {:vinculo-ativo {:id (random-uuid) :tipo (:tipo p)} :papeis (:papeis p)}))
    (vinculos-de [_ _ id] (when (perfis id) [{:tipo "servidor"}]))
    (nome-por-id [_ id] (when-let [p (perfis id)] {:nome (:nome p)}))))

(defn- fake-cadastros []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cadastros-comp/RepoCadastros
    (comissoes-vigentes [_ _ _] [{:id comissao :nome "Comissão de Finanças e Orçamento" :tipo "permanente"}])
    (nomes-de-comissoes [_ _ ids] (select-keys {comissao "Comissão de Finanças e Orçamento"
                                                comissao-extinta "Comissão Extinta"} ids))
    (roster-da-casa [_ _ _] [])
    (buscar-ente [_ id] (when (contains? @casas id) {:id id :nome-oficial "Câmara de Teste"}))))

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (let [leg (repo-leg/->RepoLegislativoPg c (outbox/bus))
            ses (repo-s/->RepoSessoesPg c (outbox/bus))]
        (binding [*leg* leg *ses* ses
                  *svc* (-> (http/servico (config/carregar)
                                          (rotas/montar {:idp (idp-dev/idp-dev) :repo-identidade (fake-identidade)
                                                         :repo-legislativo leg :repo-sessoes ses
                                                         :repo-cadastros (fake-cadastros)
                                                         :relogio (tempo/relogio-fixo agora)})
                                          it/globais)
                            ph/create-server ::ph/service-fn)]
          (try (t) (finally (component/stop c))))))))

(defn- cab [ente quem] {"authorization" (str "Bearer " (json/write-value-as-string
                                                          {:sub "u" :ente-id (str ente) :identidade-id (str quem)}))
                        "Content-Type" "application/json"})

(defn- chamar
  ([ente quem metodo url] (chamar ente quem metodo url nil))
  ([ente quem metodo url corpo]
   (let [r (apply pt/response-for *svc* metodo url
                  (concat (when quem [:headers (cab ente quem)])
                          (when corpo [:body (json/write-value-as-string corpo)])))]
     {:status (:status r)
      :corpo (when (seq (:body r)) (json/read-value (:body r) json/keyword-keys-object-mapper))})))

(defn- nova-casa! [] (let [e (random-uuid)] (swap! casas conj e) e))

(def bloco-tematico {:comissao-id (str comissao) :tema "A saúde básica nos bairros" :local "Plenário"
                     :finalidade "tematica"})

(defn- agendar! [ente tipo bloco]
  (chamar ente sec :post "/sessoes" (cond-> {:sessao-legislativa-id (str (random-uuid)) :tipo-sessao tipo
                                              :agendada-para (str inicio)}
                                      bloco (assoc :audiencia bloco))))

(defn- audiencia! [ente & [bloco]]
  (let [r (agendar! ente "audiencia_publica" (or bloco bloco-tematico))]
    (is (= 201 (:status r)) (pr-str r))
    (parse-uuid (get-in r [:corpo :id]))))

(defn- transicionar! [ente sid & paras]
  (doseq [[lv para] (map-indexed vector paras)]
    (repo-s/transicionar-sessao! *ses* ente {:id sid :para para :lock-version lv :updated-by sec})))

(defn- inscrever-cidada! [ente quem sid & [corpo]]
  (chamar ente quem :post (str "/portal/audiencias/" sid "/inscricoes")
          (merge {:fala-como "individual" :tema "Postos de saúde" :ciente-publicidade true} corpo)))

;; ---------- agendar ----------

(deftest agendar-a-audiencia
  (let [ente (nova-casa!)]
    (testing "o bloco e' obrigatorio na audiencia, e so' nela"
      (is (= 400 (:status (agendar! ente "audiencia_publica" nil))))
      (is (= 400 (:status (agendar! ente "ordinaria" bloco-tematico)))))
    (testing "a comissao tem de ser vigente nesta Casa"
      (let [r (agendar! ente "audiencia_publica" (assoc bloco-tematico :comissao-id (str comissao-extinta)))]
        (is (= 422 (:status r)))
        (is (= "comissao-id" (get-in r [:corpo :campo])))))
    (testing "metas fiscais exige a referencia do quadrimestre"
      (is (= 400 (:status (agendar! ente "audiencia_publica" (assoc bloco-tematico :finalidade "metas_fiscais")))))
      (is (= 201 (:status (agendar! ente "audiencia_publica"
                                    (assoc bloco-tematico :finalidade "metas_fiscais" :referencia "2026-Q2"))))))
    (testing "a proposicao em debate tem de ser desta Casa"
      (is (= 422 (:status (agendar! ente "audiencia_publica" (assoc bloco-tematico :proposicao-id (str (random-uuid))))))))
    (let [sid (audiencia! ente)
          s (:corpo (chamar ente sec :get (str "/sessoes/" sid)))
          a (chamar ente ver :get (str "/sessoes/" sid "/audiencia"))]
      (is (= {:tipo-sessao "audiencia_publica" :delibera false :exige-quorum false :aceita-inscricao-cidadao true
              :gera-ata-regimental true :transmite-publica true}
             (select-keys s [:tipo-sessao :delibera :exige-quorum :aceita-inscricao-cidadao :gera-ata-regimental
                             :transmite-publica])))
      (is (= 200 (:status a)) "o vereador le a audiencia")
      (is (= {:sessao-id (str sid) :numero 1 :estado "agendada" :modalidade "presencial"
              :comissao {:id (str comissao) :nome "Comissão de Finanças e Orçamento"}
              :tema "A saúde básica nos bairros" :local "Plenário" :finalidade "tematica"
              :tempo-fala-segundos 300 :inscricoes-abertas true :inscricoes []}
             (dissoc (:corpo a) :agendada-para)))
      (testing "sessao comum nao tem audiencia"
        (let [ord (parse-uuid (get-in (agendar! ente "ordinaria" nil) [:corpo :id]))]
          (is (= 404 (:status (chamar ente sec :get (str "/sessoes/" ord "/audiencia")))))
          (is (true? (get-in (chamar ente sec :get (str "/sessoes/" ord)) [:corpo :exige-quorum]))))))))

(deftest a-proposicao-em-debate-vem-com-rotulo-e-ementa
  (let [ente (nova-casa!)
        pid (:id (repo-leg/protocolar! *leg* ente {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                                                   :uf "CE" :municipio-nome "Fortaleza"
                                                   :ementa "Dispõe sobre os postos de saúde"}))
        sid (audiencia! ente (assoc bloco-tematico :proposicao-id (str pid)))
        a (:corpo (chamar ente sec :get (str "/sessoes/" sid "/audiencia")))]
    (is (= {:id (str pid) :ementa "Dispõe sobre os postos de saúde"} (dissoc (:proposicao a) :rotulo)))
    (is (re-matches #"PL \d{3}/2026" (get-in a [:proposicao :rotulo])))))

;; ---------- a Mesa ----------

(deftest a-mesa-conduz-as-falas
  (let [ente (nova-casa!) sid (audiencia! ente) base (str "/sessoes/" sid "/audiencia")]
    (testing "so' a secretaria escreve; o vereador le"
      (is (= 403 (:status (chamar ente ver :patch base {:tempo-fala-segundos 240}))))
      (is (= 403 (:status (chamar ente ver :post (str base "/inscricoes")
                                  {:nome "Maria" :fala-como "individual" :tema "Ônibus"})))))
    (is (= 240 (get-in (chamar ente sec :patch base {:tempo-fala-segundos 240}) [:corpo :tempo-fala-segundos])))
    (is (= 400 (:status (chamar ente sec :patch base {:tempo-fala-segundos 30}))))
    (is (= 400 (:status (chamar ente sec :patch base {}))))
    (let [r1 (inscrever-cidada! ente cid sid)
          p1 (chamar ente sec :post (str base "/inscricoes")
                     {:nome "Maria das Dores" :fala-como "entidade" :entidade "Associação do Bairro" :tema "Ônibus"})
          p2 (chamar ente sec :post (str base "/inscricoes") {:nome "José" :fala-como "individual" :tema "Praças"})
          [i1 i2 i3] (get-in (chamar ente sec :get base) [:corpo :inscricoes])]
      (is (= 201 (:status r1)) (pr-str r1))
      (is (= 201 (:status p1)))
      (is (= {:ordem 2 :nome "Maria das Dores" :fala-como "entidade" :entidade "Associação do Bairro" :tema "Ônibus"
              :origem "presencial_secretaria" :estado "inscrita"}
             (select-keys (:corpo p1) [:ordem :nome :fala-como :entidade :tema :origem :estado])))
      (is (re-matches #"AUD-2026-\d{6}" (get-in p1 [:corpo :protocolo])))
      (is (= [1 2 3] (map :ordem [i1 i2 i3])) "a ordem de inscricao")
      (is (= "Roberta Costa Aguiar" (:nome i1)) "o nome veio da identidade")
      (is (= "portal_govbr" (:origem i1)))
      (testing "chamar exige a audiencia aberta"
        (is (= 409 (:status (chamar ente sec :post (str base "/inscricoes/" (:id i1) "/chamada"))))))
      (transicionar! ente sid "aberta")
      (let [c1 (chamar ente sec :post (str base "/inscricoes/" (:id i1) "/chamada"))]
        (is (= 200 (:status c1)))
        (is (= "falando" (get-in c1 [:corpo :estado])))
        (is (some? (get-in c1 [:corpo :chamada-em]))))
      (testing "uma fala por vez"
        (let [r (chamar ente sec :post (str base "/inscricoes/" (:id i2) "/chamada"))]
          (is (= 409 (:status r)))
          (is (re-find #"palavra" (get-in r [:corpo :erro])))))
      (is (= 400 (:status (chamar ente sec :post (str base "/inscricoes/" (:id i1) "/encerramento") {}))))
      (let [e1 (chamar ente sec :post (str base "/inscricoes/" (:id i1) "/encerramento") {:tempo-usado-segundos 287})]
        (is (= 200 (:status e1)))
        (is (= {:estado "falou" :tempo-usado-segundos 287} (select-keys (:corpo e1) [:estado :tempo-usado-segundos]))))
      (is (= 409 (:status (chamar ente sec :post (str base "/inscricoes/" (:id i1) "/encerramento")
                                  {:tempo-usado-segundos 1}))) "ja' falou")
      (is (= 200 (:status (chamar ente sec :post (str base "/inscricoes/" (:id i2) "/chamada")))) "agora o proximo")
      (is (= "ausente" (get-in (chamar ente sec :post (str base "/inscricoes/" (:id i3) "/ausencia")) [:corpo :estado])))
      (is (= 409 (:status (chamar ente sec :post (str base "/inscricoes/" (:id i3) "/chamada")))) "ausente e' terminal")
      (testing "inscricao de outra sessao = 404"
        (let [outra (audiencia! ente)]
          (is (= 404 (:status (chamar ente sec :post (str "/sessoes/" outra "/audiencia/inscricoes/" (:id i2)
                                                          "/encerramento")
                                      {:tempo-usado-segundos 10}))))))
      (is (= 201 (:status p2))))))

;; ---------- a cidada ----------

(deftest a-cidada-se-inscreve-pelo-portal
  (let [ente (nova-casa!) sid (audiencia! ente)]
    (testing "ciente da publicidade, ou nada"
      (is (= 400 (:status (inscrever-cidada! ente cid sid {:ciente-publicidade false}))))
      (is (= 400 (:status (chamar ente cid :post (str "/portal/audiencias/" sid "/inscricoes")
                                  {:fala-como "individual" :tema "x"})))))
    (testing "fora do individual, a entidade"
      (is (= 400 (:status (inscrever-cidada! ente cid sid {:fala-como "entidade"})))))
    (testing "o nome nao vem do corpo: um `nome` no corpo e' descartado, vale o da identidade gov.br"
      (let [r (inscrever-cidada! ente cid sid {:nome "Outra Pessoa"})]
        (is (= 201 (:status r)))
        (is (= #{:protocolo :recibo-em :ordem} (set (keys (:corpo r)))))
        (is (re-matches #"AUD-2026-\d{6}" (get-in r [:corpo :protocolo])))
        (is (= 1 (get-in r [:corpo :ordem])))
        (is (= ["Roberta Costa Aguiar"]
               (map :nome (get-in (chamar ente sec :get (str "/sessoes/" sid "/audiencia")) [:corpo :inscricoes]))))))
    (testing "uma inscricao por audiencia"
      (let [r (inscrever-cidada! ente cid sid)]
        (is (= 409 (:status r)))
        (is (re-find #"inscrit" (get-in r [:corpo :erro])))))
    (is (= 2 (get-in (inscrever-cidada! ente cid2 sid) [:corpo :ordem])))
    (testing "minhas inscricoes"
      (let [r (chamar ente cid :get "/portal/minhas-inscricoes")
            [i] (get-in r [:corpo :inscricoes])]
        (is (= 200 (:status r)))
        (is (= 1 (count (get-in r [:corpo :inscricoes]))) "so' as dela")
        (is (= {:sessao-id (str sid) :tema "A saúde básica nos bairros"
                :comissao-nome "Comissão de Finanças e Orçamento" :ordem 1 :estado "inscrita"}
               (select-keys i [:sessao-id :tema :comissao-nome :ordem :estado])))
        (testing "desistencia so' do dono"
          (is (= 404 (:status (chamar ente cid2 :post (str "/portal/minhas-inscricoes/" (:id i) "/desistencia")))))
          (let [d (chamar ente cid :post (str "/portal/minhas-inscricoes/" (:id i) "/desistencia"))]
            (is (= 200 (:status d)))
            (is (= "desistiu" (get-in d [:corpo :estado]))))
          (is (= 409 (:status (chamar ente cid :post (str "/portal/minhas-inscricoes/" (:id i) "/desistencia"))))))
        (testing "desistiu, pode voltar: ganha o fim da fila"
          (is (= 3 (get-in (inscrever-cidada! ente cid sid) [:corpo :ordem]))))))
    (testing "inscricoes fechadas pela Mesa"
      (chamar ente sec :patch (str "/sessoes/" sid "/audiencia") {:inscricoes-abertas false})
      (let [r (inscrever-cidada! ente ver sid)]
        (is (= 409 (:status r)))
        (is (re-find #"fechadas" (get-in r [:corpo :erro]))))
      (is (= 201 (:status (chamar ente sec :post (str "/sessoes/" sid "/audiencia/inscricoes")
                                  {:nome "Chegou agora" :fala-como "individual" :tema "Iluminação"})))
          "a Mesa ainda inscreve quem chegou"))
    (testing "sessao que nao e' audiencia: 404"
      (let [ord (parse-uuid (get-in (agendar! ente "ordinaria" nil) [:corpo :id]))]
        (is (= 404 (:status (inscrever-cidada! ente cid ord))))))
    (testing "sem login: 401"
      (is (= 401 (:status (chamar ente nil :post (str "/portal/audiencias/" sid "/inscricoes")
                                  {:fala-como "individual" :tema "x" :ciente-publicidade true})))))))

(deftest rls-entre-casas
  (let [a (nova-casa!) b (nova-casa!) sid (audiencia! a)]
    (is (= 404 (:status (chamar b sec :get (str "/sessoes/" sid "/audiencia")))))
    (is (= 404 (:status (inscrever-cidada! b cid sid))) "a cidada de outra Casa nao alcanca esta audiencia")
    (is (= 404 (:status (chamar b nil :get (str "/portal/casa/" b "/audiencias/" sid)))))
    (is (= [] (get-in (chamar b nil :get (str "/portal/casa/" b "/audiencias")) [:corpo :proximas])))
    (let [i (get-in (inscrever-cidada! a cid sid) [:corpo])
          id (:id (first (get-in (chamar a cid :get "/portal/minhas-inscricoes") [:corpo :inscricoes])))]
      (is (some? (:protocolo i)))
      (is (= [] (get-in (chamar b cid :get "/portal/minhas-inscricoes") [:corpo :inscricoes])))
      (is (= 404 (:status (chamar b cid :post (str "/portal/minhas-inscricoes/" id "/desistencia"))))))))

;; ---------- o portal ----------

(deftest o-portal-mostra-a-audiencia
  (let [ente (nova-casa!)
        sid (audiencia! ente)
        meta (audiencia! ente (assoc bloco-tematico :tema "Metas fiscais do 1º quadrimestre" :finalidade "metas_fiscais"
                                     :referencia "2026-Q1"))
        url (str "/portal/casa/" ente "/audiencias")]
    (is (= 404 (:status (chamar ente nil :get (str "/portal/casa/" (random-uuid) "/audiencias")))))
    (let [l (:corpo (chamar ente nil :get url))]
      (is (= #{(str sid) (str meta)} (set (map :sessao-id (:proximas l)))))
      (is (= [] (:realizadas l)))
      (is (= {:tema "A saúde básica nos bairros" :comissao-nome "Comissão de Finanças e Orçamento" :estado "agendada"
              :local "Plenário" :finalidade "tematica"}
             (select-keys (first (filter #(= (str sid) (:sessao-id %)) (:proximas l)))
                          [:tema :comissao-nome :estado :local :finalidade]))))
    (inscrever-cidada! ente cid sid)
    (inscrever-cidada! ente cid2 sid)
    (let [[i1 i2] (get-in (chamar ente sec :get (str "/sessoes/" sid "/audiencia")) [:corpo :inscricoes])
          base (str "/sessoes/" sid "/audiencia/inscricoes/")]
      (chamar ente cid2 :post (str "/portal/minhas-inscricoes/" (:id i2) "/desistencia"))
      (let [p (:corpo (chamar ente nil :get (str url "/" sid)))]
        (is (= {:inscricoes-abertas true :inscritos 1 :ata-publicada false :falaram [] :tempo-fala-segundos 300
                :modalidade "presencial"}
               (select-keys p [:inscricoes-abertas :inscritos :ata-publicada :falaram :tempo-fala-segundos :modalidade])))
        (is (not (contains? p :referencia))))
      (is (= "2026-Q1" (get-in (chamar ente nil :get (str url "/" meta)) [:corpo :referencia])))
      (transicionar! ente sid "aberta")
      (chamar ente sec :post (str base (:id i1) "/chamada"))
      (is (= [] (get-in (chamar ente nil :get (str url "/" sid)) [:corpo :falaram])) "antes de encerrada, nada nominal")
      (chamar ente sec :post (str base (:id i1) "/encerramento") {:tempo-usado-segundos 200})
      (repo-s/transicionar-sessao! *ses* ente {:id sid :para "encerrada" :lock-version 1 :updated-by sec})
      (is (= 201 (:status (chamar ente sec :post (str "/sessoes/" sid "/ata") {:texto "Ata da audiência pública."}))))
      (let [p (:corpo (chamar ente nil :get (str url "/" sid)))]
        (is (= [{:nome "Roberta Costa Aguiar" :fala-como "individual"}] (:falaram p)) "quem desistiu nao aparece")
        (is (false? (:inscricoes-abertas p)) "acabada, as inscricoes fecham")
        (is (true? (:ata-publicada p)))
        (is (= "encerrada" (:estado p))))
      (is (= [(str sid)] (map :sessao-id (get-in (chamar ente nil :get url) [:corpo :realizadas])))))
    (testing "audiencia sem transmissao publica nao aparece no portal"
      (let [sid2 (audiencia! ente)]
        (jdbc/execute! (:ds (:datasource *ses*))
                            ["update sessoes.sessao set transmite_publica = false where ente_id = ? and id = ?" ente sid2])
        (is (= 404 (:status (chamar ente nil :get (str url "/" sid2)))))
        (is (not-any? #(= (str sid2) (:sessao-id %)) (get-in (chamar ente nil :get url) [:corpo :proximas])))
        (is (= 404 (:status (inscrever-cidada! ente cid sid2))))))))

;; ---------- o que a audiencia muda no resto ----------

(deftest sessao-que-nao-delibera-nao-abre-votacao
  (let [ente (nova-casa!)
        pid (:id (repo-leg/protocolar! *leg* ente {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                                                   :uf "CE" :municipio-nome "Fortaleza" :ementa "Teste"}))
        votar (fn [sid] (chamar ente sec :post (str "/sessoes/" sid "/votacoes")
                                {:objeto-tipo "proposicao" :objeto-id (str pid) :modalidade "simbolica"
                                 :quorum-tipo "maioria_simples"}))]
    (doseq [tipo ["solene" "especial"]]
      (let [sid (parse-uuid (get-in (agendar! ente tipo nil) [:corpo :id]))]
        (transicionar! ente sid "aberta")
        (let [r (votar sid)]
          (is (= 409 (:status r)) tipo)
          (is (= "esta sessão não delibera" (get-in r [:corpo :erro]))))))
    (let [sid (audiencia! ente)]
      (transicionar! ente sid "aberta")
      (is (= 409 (:status (votar sid)))))
    (let [sid (parse-uuid (get-in (agendar! ente "ordinaria" nil) [:corpo :id]))]
      (transicionar! ente sid "aberta")
      (is (= 201 (:status (votar sid))) "a ordinaria segue votando"))))

(deftest o-quorum-diz-se-a-sessao-exige
  (let [ente (nova-casa!) sid (audiencia! ente)
        ord (parse-uuid (get-in (agendar! ente "ordinaria" nil) [:corpo :id]))]
    (is (false? (get-in (chamar ente sec :get (str "/sessoes/" sid "/quorum")) [:corpo :exige-quorum])))
    (is (true? (get-in (chamar ente sec :get (str "/sessoes/" ord "/quorum")) [:corpo :exige-quorum])))))

(deftest a-audiencia-nao-e-a-sessao-anterior-da-leitura-da-ata
  (let [ente (nova-casa!)
        sla (random-uuid)
        sessao! (fn [tipo bloco quando]
                  (parse-uuid (get-in (chamar ente sec :post "/sessoes"
                                              (cond-> {:sessao-legislativa-id (str sla) :tipo-sessao tipo
                                                       :agendada-para (str quando)}
                                                bloco (assoc :audiencia bloco)))
                                      [:corpo :id])))
        ord1 (sessao! "ordinaria" nil (Instant/parse "2026-09-01T12:00:00Z"))
        aud (sessao! "audiencia_publica" bloco-tematico (Instant/parse "2026-09-08T12:00:00Z"))
        ord2 (sessao! "ordinaria" nil (Instant/parse "2030-01-01T12:00:00Z"))]
    (doseq [s [ord1 aud]]
      (transicionar! ente s "aberta" "encerrada")
      (is (= 201 (:status (chamar ente sec :post (str "/sessoes/" s "/ata") {:texto "Ata."})))))
    (is (= (str ord1) (get-in (chamar ente sec :get (str "/sessoes/" ord2 "/leitura-ata")) [:corpo :anterior :id]))
        "a ordinaria le a ata da ordinaria anterior, nao a da audiencia no meio")))

(deftest o-rascunho-da-ata-conhece-quem-falou
  (let [ente (nova-casa!) sid (audiencia! ente) base (str "/sessoes/" sid "/audiencia/inscricoes/")]
    (inscrever-cidada! ente cid sid)
    (let [[i] (get-in (chamar ente sec :get (str "/sessoes/" sid "/audiencia")) [:corpo :inscricoes])]
      (transicionar! ente sid "aberta")
      (chamar ente sec :post (str base (:id i) "/chamada"))
      (chamar ente sec :post (str base (:id i) "/encerramento") {:tempo-usado-segundos 120})
      (let [c (rotas/contexto-para-ia *ses* (fake-cadastros) ente sid)
            fala (first (filter #(= "manifestacao_cidada" (:tipo-fala %)) (:falas c)))]
        (is (some? fala) "a fala do cidadao entra no contexto da IA")
        (is (= (parse-uuid (:id i)) (:orador-id fala)))
        (is (some? (:iniciou-em fala)))
        (is (= "Roberta Costa Aguiar" (get (:nomes c) (:orador-id fala))) "com o nome, ao lado dos vereadores")
        (is (not (contains? c :falas-cidadas)) "o host funde e limpa")))))
