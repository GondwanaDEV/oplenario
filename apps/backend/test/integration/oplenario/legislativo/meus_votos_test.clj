(ns oplenario.legislativo.meus-votos-test
  "INTEGRACAO (PG real + borda Pedestal, composicao de PRODUCAO via `rotas/montar`) — GET /meu/votos, a tela 'Minha
  atuacao' do vereador: o vereador autenticado ve TODOS os seus votos nominais, inclusive os de sessao secreta ou
  fechada ao publico, e SO' ele. O perfil PUBLICO (`/portal/.../vereadores/:id`, regra do #145) nao muda: o mesmo voto
  que esta rota devolve nao aparece nele.

  Dado semeado pelos atos REAIS (sessoes -> legislativo -> outbox -> relay -> consumer de transparencia), para o
  controle positivo (o voto escondido existe no read-model) e a comparacao com o perfil publico serem de verdade."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.components.repositorio :as repo-cadastros-comp]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.migracao :as migracao]
            [oplenario.rotas :as rotas]
            [oplenario.sessoes.components.repositorio :as repo-s]
            [oplenario.transparencia.components.repositorio :as transparencia-repo]
            [oplenario.transparencia.diplomat.consumers :as consumers])
  (:import (java.time Instant LocalDate)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *leg* nil)
(def ^:dynamic *ses* nil)
(def ^:dynamic *tra* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)
                *leg* (repo-leg/->RepoLegislativoPg c (outbox/bus))
                *ses* (repo-s/->RepoSessoesPg c (outbox/bus))
                *tra* (transparencia-repo/->RepoTransparenciaPg c)]
        (try (t) (finally (component/stop c)))))))

;; ---------- cenario ----------

(def helena (random-uuid))
(def rui (random-uuid))
(def nomes {helena "Helena Past" rui "Rui Nogueira"})
(def identidade-helena (random-uuid))
(def identidade-rui (random-uuid))
(def identidade-secretaria (random-uuid))
(def identidade-sem-cadastro (random-uuid))

(def ^:private papeis
  {identidade-helena #{"vereador"} identidade-rui #{"vereador"}
   identidade-secretaria #{"secretario"} identidade-sem-cadastro #{"vereador"}})

(def ^:private vereador-da-identidade {identidade-helena helena identidade-rui rui})

(def ^:private janela-larga [{:inicio (LocalDate/of 2000 1 1) :fim nil}])

(defn- ficha [vereador-id]
  {:ficha {:vereador {:id vereador-id :nome (get nomes vereador-id) :nome-parlamentar (get nomes vereador-id)
                      :identidade-id (random-uuid) :ente-id (random-uuid)}
           :mandato {:partido "PT" :estado "ativo" :natureza "titular"}
           :legislatura nil
           :comissoes []}
   :janelas janela-larga})

(defn- servico [casas]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev) :repo-legislativo *leg* :repo-sessoes *ses*
                                   :repo-transparencia *tra*
                                   :repo-identidade
                                   #_{:clj-kondo/ignore [:missing-protocol-method]}
                                   (reify repo-id/RepoIdentidade
                                     (snapshot-ator [_ _ente identidade-id]
                                       {:vinculo-ativo {:id (random-uuid) :tipo "vereador"}
                                        :papeis (get papeis identidade-id #{})}))
                                   :repo-cadastros
                                   #_{:clj-kondo/ignore [:missing-protocol-method]}
                                   (reify repo-cadastros-comp/RepoCadastros
                                     (listar-vereadores [_ _ _]
                                       (mapv (fn [[id nome]] {:id id :nome nome}) nomes))
                                     (vereador-por-identidade [_ _ente identidade-id]
                                       (when-let [v (vereador-da-identidade identidade-id)] {:id v})))
                                   :ficha-e-janelas-publicas (fn [_ente vereador-id]
                                                               (when (contains? nomes vereador-id) (ficha vereador-id)))
                                   :info-ente #(when (contains? casas %) {:nome-oficial "Câmara"})})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente identidade]
  (json/write-value-as-string {:sub "u" :ente-id (str ente) :identidade-id (str identidade)}))

(defn- meus-votos
  "GET /meu/votos como `identidade` na Casa `ente`."
  ([svc ente identidade] (meus-votos svc ente identidade ""))
  ([svc ente identidade query]
   (let [r (pt/response-for svc :get (str "/meu/votos" query)
                            :headers {"authorization" (str "Bearer " (token ente identidade))})]
     (assoc r :json (when (= 200 (:status r))
                      (json/read-value (:body r) json/keyword-keys-object-mapper))))))

(defn- perfil-publico [svc ente vereador]
  (let [r (pt/response-for svc :get (str "/portal/casa/" ente "/vereadores/" vereador))]
    (assoc r :json (when (= 200 (:status r))
                     (json/read-value (:body r) json/keyword-keys-object-mapper)))))

(defn- sessao! [ente tipo]
  (:id (repo-s/agendar-sessao! *ses* ente {:id (random-uuid) :sessao-legislativa-id (random-uuid) :tipo-sessao tipo
                                           :modalidade "presencial" :agendada-para (Instant/parse "2026-09-10T12:00:00Z")})))

(defn- fechar-ao-publico! [ente sessao-id]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (jdbc/execute! tx ["UPDATE sessoes.sessao SET transmite_publica = false WHERE ente_id = ? AND id = ?"
                                ente sessao-id]))))

(defn- proposicao! [ente ementa]
  (:id (repo-leg/protocolar! *leg* ente
         {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE" :municipio-nome "Fortaleza"
          :ementa ementa :autor-tipo "vereador" :autor-id helena})))

(defn- votacao-nominal!
  "Votacao NOMINAL sobre `objeto-id` (nil = uma proposicao qualquer, nao protocolada), com um voto de cada vereador
  (helena `voto-h`, rui `voto-r`). `sessao-id` nil = votacao fora de plenario."
  ([ente sessao-id voto-h voto-r] (votacao-nominal! ente sessao-id voto-h voto-r (random-uuid)))
  ([ente sessao-id voto-h voto-r objeto-id]
   (let [vid (random-uuid)]
     (repo-leg/abrir-votacao! *leg* ente (cond-> {:id vid :objeto-tipo "proposicao" :objeto-id objeto-id
                                                  :modalidade "nominal" :quorum-tipo "maioria_simples"}
                                           sessao-id (assoc :sessao-id sessao-id)))
     (doseq [[v voto] [[helena voto-h] [rui voto-r]]]
       (repo-leg/registrar-voto! *leg* ente {:id (random-uuid) :votacao-id vid :vereador-id v :voto voto}))
     vid)))

(defn- votacao-secreta!
  "Votacao SECRETA na sessao: dois votos anonimos (sem vereador_id)."
  [ente sessao-id]
  (let [vid (random-uuid)]
    (repo-leg/abrir-votacao! *leg* ente {:id vid :objeto-tipo "proposicao" :objeto-id (random-uuid)
                                         :modalidade "secreta" :quorum-tipo "maioria_simples" :sessao-id sessao-id})
    (doseq [voto ["sim" "nao"]]
      (repo-leg/registrar-voto-secreto! *leg* ente {:id (random-uuid) :votacao-id vid :voto voto}))
    vid))

(defn- anular! [ente votacao-id]
  (let [lv (tenancy/com-tenant* *ds* ente
             (fn [tx] (:votacoes/lock_version
                       (jdbc/execute-one! tx ["SELECT lock_version FROM legislativo.votacoes WHERE ente_id = ? AND id = ?"
                                              ente votacao-id]))))]
    (repo-leg/anular-votacao! *leg* ente {:id votacao-id :lock-version lv :updated-by (random-uuid)})))

(defn- casa!
  "Uma Casa com 4 votacoes nominais (helena e rui votam em todas) + 1 votacao secreta + 1 fora de plenario:
  na publica (helena sim, rui nao), na secreta (helena nao, rui sim), na fechada ao publico (os dois abstencao) e
  fora de plenario (helena sim, rui sim). Devolve os ids."
  []
  (let [ente (random-uuid)
        publica (sessao! ente "ordinaria")
        secreta (sessao! ente "secreta")
        fechada (sessao! ente "ordinaria")
        _ (fechar-ao-publico! ente fechada)
        materia (proposicao! ente "Dispõe sobre a poda de árvores na orla")
        na-publica (votacao-nominal! ente publica "sim" "nao" materia)
        na-secreta (votacao-nominal! ente secreta "nao" "sim")
        na-fechada (votacao-nominal! ente fechada "abstencao" "abstencao")
        fora-de-plenario (votacao-nominal! ente nil "sim" "sim")
        votacao-secreta (votacao-secreta! ente secreta)]
    (outbox/drenar! *ds* (consumers/registrar {}))
    {:ente ente :materia materia :na-publica na-publica :na-secreta na-secreta :na-fechada na-fechada
     :fora-de-plenario fora-de-plenario :votacao-secreta votacao-secreta}))

(defn- por-votacao [json] (into {} (map (juxt :votacao-id identity)) (:votos json)))

;; ---------- as regras ----------

(deftest controle-positivo-os-votos-escondidos-existem-na-fonte-e-no-read-model
  ;; sem isto, "aparece na rota do vereador" nao distinguiria dado semeado de dado inventado pela rota.
  (let [{:keys [ente]} (casa!)
        n (tenancy/com-tenant* *ds* ente
            (fn [tx] (:count (jdbc/execute-one! tx ["SELECT count(*) FROM legislativo.votos WHERE ente_id = ?" ente]))))
        projetados (tenancy/com-tenant* *ds* ente
                     (fn [tx] (:count (jdbc/execute-one! tx ["SELECT count(*) FROM transparencia.voto_parlamentar WHERE ente_id = ?" ente]))))]
    (is (= 8 n) "4 votacoes nominais x 2 votos (a secreta mora noutra tabela, sem vereador)")
    (is (= 6 projetados) "o read-model so' projeta voto de votacao COM sessao: 3 votacoes x 2 votos")))

(deftest o-vereador-ve-o-proprio-voto-de-sessao-secreta-e-fechada-marcado
  (let [{:keys [ente na-publica na-secreta na-fechada fora-de-plenario]} (casa!)
        svc (servico #{ente})
        {:keys [status json]} (meus-votos svc ente identidade-helena)
        v (por-votacao json)]
    (is (= 200 status))
    (is (= (str helena) (:vereador-id json)))
    (testing "os quatro votos nominais dela, inclusive os de sessao nao publica"
      (is (= #{(str na-publica) (str na-secreta) (str na-fechada) (str fora-de-plenario)} (set (keys v)))))
    (testing "cada voto diz o que o portal faz com ele"
      (is (= "publico" (:portal (v (str na-publica)))))
      (is (= "sessao-fechada" (:portal (v (str na-secreta)))) "sessao secreta: so' ela ve")
      (is (= "sessao-fechada" (:portal (v (str na-fechada)))) "sessao fechada ao publico: so' ela ve")
      (is (= "sem-sessao" (:portal (v (str fora-de-plenario)))) "votacao fora de plenario: o portal nao publica"))
    (testing "o valor do voto e' o dela"
      (is (= "sim" (:voto (v (str na-publica)))))
      (is (= "nao" (:voto (v (str na-secreta)))))
      (is (= "abstencao" (:voto (v (str na-fechada))))))
    (testing "os numeros contam todos os votos nominais dela"
      (is (= 4 (:votos-total json)))
      (is (= {:sim 2 :nao 1 :abstencao 1} (:votos-por-opcao json))))
    (testing "nenhum voto esta marcado como anulado"
      (is (every? (comp false? :anulada) (:votos json))))))

(deftest a-materia-votada-vem-com-tipo-numero-e-ementa
  (let [{:keys [ente na-publica na-secreta]} (casa!)
        {:keys [json]} (meus-votos (servico #{ente}) ente identidade-helena)
        v (por-votacao json)]
    (is (= {:materia-tipo "projeto_lei" :materia-ano 2026 :materia-ementa "Dispõe sobre a poda de árvores na orla"}
           (select-keys (v (str na-publica)) [:materia-tipo :materia-ano :materia-ementa])))
    (is (pos-int? (:materia-sequencial (v (str na-publica)))))
    (testing "votacao cujo objeto nao e' uma proposicao protocolada: sem materia, a linha continua la'"
      (is (nil? (:materia-tipo (v (str na-secreta)))))
      (is (nil? (:materia-ementa (v (str na-secreta))))))))

(deftest o-perfil-publico-nao-muda-o-voto-que-so-ela-ve-nao-aparece-la
  ;; o corolario da regra do #145: a rota autenticada devolve o voto de sessao nao publica; o perfil PUBLICO segue sem ele.
  (let [{:keys [ente na-publica na-secreta na-fechada fora-de-plenario]} (casa!)
        svc (servico #{ente})
        ids-meus (set (map :votacao-id (:votos (:json (meus-votos svc ente identidade-helena)))))
        pub (:json (perfil-publico svc ente helena))
        ids-publicos (set (map :votacao-id (:votos pub)))]
    (is (contains? ids-meus (str na-secreta)) "controle: o voto de sessao secreta esta na rota autenticada")
    (is (= #{(str na-publica)} ids-publicos) "o perfil publico mostra so' o voto da sessao publica")
    (is (not-any? ids-publicos (map str [na-secreta na-fechada fora-de-plenario])))
    (is (= 1 (:votos-total pub)))
    (is (= {:sim 1 :nao 0 :abstencao 0} (:votos-por-opcao pub))
        "o numero publico continua sem o voto escondido, mesmo agora que ela o ve")))

(deftest o-voto-de-outro-vereador-nunca-aparece
  (let [{:keys [ente na-secreta]} (casa!)
        svc (servico #{ente})
        helena-json (:json (meus-votos svc ente identidade-helena))
        rui-json (:json (meus-votos svc ente identidade-rui))]
    (is (= (str rui) (:vereador-id rui-json)))
    (testing "mesmas votacoes, mas cada um ve o PROPRIO valor: helena votou nao e rui votou sim na secreta"
      (is (= "nao" (:voto ((por-votacao helena-json) (str na-secreta)))))
      (is (= "sim" (:voto ((por-votacao rui-json) (str na-secreta))))))
    (testing "os numeros sao do dono"
      (is (= {:sim 2 :nao 1 :abstencao 1} (:votos-por-opcao helena-json)))
      (is (= {:sim 2 :nao 1 :abstencao 1} (:votos-por-opcao rui-json))
          "rui: sim na secreta, nao na publica, abstencao na fechada, sim fora de plenario"))))

(deftest anti-forja-id-na-query-ou-no-caminho-nao-escolhe-de-quem-sao-os-votos
  (let [{:keys [ente na-secreta]} (casa!)
        svc (servico #{ente})
        {:keys [json]} (meus-votos svc ente identidade-helena (str "?vereador-id=" rui "&vereador=" rui))]
    (is (= (str helena) (:vereador-id json)))
    (is (= "nao" (:voto ((por-votacao json) (str na-secreta)))) "o voto de helena, nao o de rui")
    (testing "nao ha' rota com o id do vereador no caminho"
      (is (= 404 (:status (pt/response-for svc :get (str "/meu/votos/" rui)
                                           :headers {"authorization" (str "Bearer " (token ente identidade-helena))})))))))

(deftest outra-casa-nunca-vaza
  ;; o MESMO vereador-id em duas Casas (identidade e cadastro valem nas duas, como no teste do perfil publico): cada Casa
  ;; devolve so' os votos dela.
  (let [a (casa!) b (casa!)
        svc (servico #{(:ente a) (:ente b)})
        ja (:json (meus-votos svc (:ente a) identidade-helena))
        jb (:json (meus-votos svc (:ente b) identidade-helena))]
    (is (= #{(str (:na-publica a)) (str (:na-secreta a)) (str (:na-fechada a)) (str (:fora-de-plenario a))}
           (set (keys (por-votacao ja)))))
    (is (= #{(str (:na-publica b)) (str (:na-secreta b)) (str (:na-fechada b)) (str (:fora-de-plenario b))}
           (set (keys (por-votacao jb)))))
    (is (= 4 (:votos-total ja) (:votos-total jb)))))

(deftest quem-nao-e-vereador-recebe-403
  (let [{:keys [ente]} (casa!)
        svc (servico #{ente})]
    (is (= 403 (:status (meus-votos svc ente identidade-secretaria))) "secretaria nao le voto por esta rota")
    (is (= 401 (:status (pt/response-for svc :get "/meu/votos"))) "sem credencial")))

(deftest o-voto-de-votacao-secreta-nao-aparece-nem-vira-numero
  ;; a votacao secreta tem 2 votos anonimos (sim, nao); nenhuma linha dela pode estar na rota, e os numeros nao a contam.
  (let [{:keys [ente votacao-secreta]} (casa!)
        {:keys [json]} (meus-votos (servico #{ente}) ente identidade-helena)]
    (is (not (contains? (por-votacao json) (str votacao-secreta))))
    (is (= 4 (:votos-total json)) "so' os 4 votos nominais; os 2 secretos nao entram")
    (is (not (re-find (re-pattern (str votacao-secreta)) (pr-str json))))))

(deftest voto-de-votacao-anulada-aparece-marcado-e-nao-conta
  ;; coerente com o #160 (o portal tira a anulada do perfil): aqui a anulada fica visivel para o dono, marcada, e nao vira numero.
  (let [ente (random-uuid)
        sessao (sessao! ente "ordinaria")
        valida (votacao-nominal! ente sessao "sim" "nao")
        anulada (votacao-nominal! ente sessao "nao" "sim")
        _ (anular! ente anulada)
        {:keys [json]} (meus-votos (servico #{ente}) ente identidade-helena)
        v (por-votacao json)]
    (is (= false (:anulada (v (str valida)))))
    (is (= true (:anulada (v (str anulada)))) "o voto da votacao anulada aparece, marcado")
    (is (= 2 (:votos-total json)) "o universo da lista inclui a anulada")
    (is (= {:sim 1 :nao 0 :abstencao 0} (:votos-por-opcao json)) "helena votou nao na anulada: nao vira numero")))

(deftest sessao-que-fecha-depois-passa-de-publico-a-sessao-fechada
  (let [ente (random-uuid)
        sessao (sessao! ente "ordinaria")
        vid (votacao-nominal! ente sessao "sim" "nao")
        svc (servico #{ente})
        portal #(:portal ((por-votacao (:json (meus-votos svc ente identidade-helena))) (str vid)))]
    (is (= "publico" (portal)))
    (fechar-ao-publico! ente sessao)
    (is (= "sessao-fechada" (portal)) "a marca segue a sessao agora: a tela nao promete publicidade que acabou")))

(deftest vereador-sem-cadastro-nesta-casa-recebe-lista-vazia
  (let [{:keys [ente]} (casa!)
        {:keys [status json]} (meus-votos (servico #{ente}) ente identidade-sem-cadastro)]
    (is (= 200 status))
    (is (= {:vereador-id nil :votos [] :votos-total 0 :votos-por-opcao {:sim 0 :nao 0 :abstencao 0}} json))))
