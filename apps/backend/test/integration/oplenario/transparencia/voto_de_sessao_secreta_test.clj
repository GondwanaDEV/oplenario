(ns oplenario.transparencia.voto-de-sessao-secreta-test
  "INTEGRACAO (PG real + borda Pedestal, composicao de PRODUCAO via `rotas/montar`) — o voto NOMINAL de sessao que o
  portal nao mostra (secreta, ou fechada ao publico) nao sai por NENHUMA leitura publica de voto por vereador:
  o CSV de dados abertos (`votos-nominais.csv`), a contagem do catalogo, e o perfil publico do vereador (a lista
  'como votou', o total e os numeros por opcao).

  O dado e' semeado pelo caminho REAL (sessoes -> legislativo -> outbox -> relay -> consumer de transparencia): a
  linha de `transparencia.voto_parlamentar` existe de verdade, e o que se prova e' que a LEITURA a esconde — o
  que cobre tambem os votos JA' projetados antes desta regra, sem reprocessar evento nenhum. Fail-closed: voto de
  votacao sem sessao conhecida (linha sem votacao no legislativo) tambem nao sai. O agregado usa o MESMO conjunto
  do voto: se contasse o voto escondido, o numero publico o denunciaria."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.components.repositorio :as repo-cadastros-comp]
            [oplenario.config :as config]
            [oplenario.http :as http]
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
                                   :repo-cadastros
                                   #_{:clj-kondo/ignore [:missing-protocol-method]}
                                   (reify repo-cadastros-comp/RepoCadastros
                                     (listar-vereadores [_ _ _]
                                       (mapv (fn [[id nome]] {:id id :nome nome}) nomes)))
                                   :ficha-e-janelas-publicas (fn [_ente vereador-id]
                                                               (when (contains? nomes vereador-id) (ficha vereador-id)))
                                   :info-ente #(when (contains? casas %) {:nome-oficial "Câmara"})})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- get-json [svc url]
  (let [r (pt/response-for svc :get url)]
    (assoc r :json (when (= 200 (:status r))
                     (json/read-value (:body r) json/keyword-keys-object-mapper)))))

(defn- sessao! [ente tipo]
  (:id (repo-s/agendar-sessao! *ses* ente {:id (random-uuid) :sessao-legislativa-id (random-uuid) :tipo-sessao tipo
                                           :modalidade "presencial" :agendada-para (Instant/parse "2026-09-10T12:00:00Z")})))

(defn- fechar-ao-publico! [ente sessao-id]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (jdbc/execute! tx ["UPDATE sessoes.sessao SET transmite_publica = false WHERE ente_id = ? AND id = ?"
                                ente sessao-id]))))

(defn- votacao-nominal!
  "Votacao NOMINAL na `sessao-id`, com um voto de cada vereador (helena `voto-h`, rui `voto-r`)."
  [ente sessao-id voto-h voto-r]
  (let [vid (random-uuid)]
    (repo-leg/abrir-votacao! *leg* ente {:id vid :objeto-tipo "proposicao" :objeto-id (random-uuid)
                                         :modalidade "nominal" :quorum-tipo "maioria_simples" :sessao-id sessao-id})
    (doseq [[v voto] [[helena voto-h] [rui voto-r]]]
      (repo-leg/registrar-voto! *leg* ente {:id (random-uuid) :votacao-id vid :vereador-id v :voto voto}))
    vid))

(defn- projetar-voto-de-votacao-desconhecida!
  "Uma linha no read-model cuja votacao o legislativo nao conhece (votacao fora de plenario, linha antiga) — pelo
  mesmo `projetar-evento!` do consumer."
  [ente vereador voto]
  (let [vid (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (transparencia-repo/projetar-evento! tx
          {:tipo "voto.registrado" :ente-id ente
           :payload {:votacao-id (str vid) :modalidade "nominal" :vereador-id (str vereador) :voto voto
                     :ocorrido-em "2026-09-10T15:00:00Z"}})))
    vid))

(defn- casa!
  "Uma Casa com 3 votacoes nominais projetadas (2 votos cada, helena e rui) + 1 voto de votacao sem sessao conhecida:
  na publica (helena sim, rui nao), na secreta (helena nao, rui sim), na fechada ao publico (os dois abstencao), e
  o voto solto de helena (nao). Devolve os ids."
  []
  (let [ente (random-uuid)
        publica (sessao! ente "ordinaria")
        secreta (sessao! ente "secreta")
        fechada (sessao! ente "ordinaria")
        _ (fechar-ao-publico! ente fechada)
        na-publica (votacao-nominal! ente publica "sim" "nao")
        na-secreta (votacao-nominal! ente secreta "nao" "sim")
        na-fechada (votacao-nominal! ente fechada "abstencao" "abstencao")
        sem-sessao (projetar-voto-de-votacao-desconhecida! ente helena "nao")]
    (outbox/drenar! *ds* (consumers/registrar {}))
    {:ente ente :na-publica na-publica :na-secreta na-secreta :na-fechada na-fechada :sem-sessao sem-sessao}))

(defn- linhas-projetadas [ente]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (:count (jdbc/execute-one! tx ["SELECT count(*) FROM transparencia.voto_parlamentar WHERE ente_id = ?" ente])))))

(defn- ids-do-csv [csv]
  (into #{} (keep #(second (re-matches #"﻿?([0-9a-f-]{36}),.*" %))) (str/split-lines csv)))

;; ---------- as regras ----------

(deftest controle-positivo-o-voto-escondido-existe-no-read-model
  ;; sem isto, "nao saiu" valeria tambem para "nunca foi projetado".
  (let [{:keys [ente]} (casa!)]
    (is (= 7 (linhas-projetadas ente)) "3 votacoes x 2 votos + 1 voto sem sessao: tudo projetado, nada barrado la'")))

(deftest o-csv-de-votos-nominais-so-tem-votacao-de-sessao-publica
  (let [{:keys [ente na-publica na-secreta na-fechada sem-sessao]} (casa!)
        svc (servico #{ente})
        r (pt/response-for svc :get (str "/portal/casa/" ente "/dados-abertos/votos-nominais.csv"))
        ids (ids-do-csv (:body r))]
    (is (= 200 (:status r)))
    (testing "a votacao de sessao publica sai, com os dois votos"
      (is (= #{(str na-publica)} ids))
      (is (str/includes? (:body r) (str helena ",Helena Past,sim")))
      (is (str/includes? (:body r) (str rui ",Rui Nogueira,nao"))))
    (testing "sessao secreta, sessao fechada ao publico e votacao sem sessao conhecida nao saem"
      (is (not-any? #(str/includes? (:body r) (str %)) [na-secreta na-fechada sem-sessao])))
    (testing "so' as 2 linhas da publica (+ cabecalho)"
      (is (= 3 (count (str/split-lines (:body r))))))))

(deftest o-catalogo-conta-so-o-que-o-csv-publica
  (let [{:keys [ente]} (casa!)
        svc (servico #{ente})
        {:keys [json status]} (get-json svc (str "/portal/casa/" ente "/dados-abertos"))
        por-chave (into {} (map (juxt :chave identity)) (:datasets json))]
    (is (= 200 status))
    (is (= 2 (get-in por-chave ["votos-nominais" :linhas]))
        "o numero de linhas do catalogo nao pode denunciar os 5 votos escondidos")))

(deftest o-perfil-publico-so-mostra-e-so-conta-voto-de-sessao-publica
  (let [{:keys [ente na-publica na-secreta na-fechada sem-sessao]} (casa!)
        svc (servico #{ente})
        {:keys [status json]} (get-json svc (str "/portal/casa/" ente "/vereadores/" helena))]
    (is (= 200 status))
    (testing "a lista 'como votou' tem so' o voto da sessao publica"
      (is (= [(str na-publica)] (mapv :votacao-id (:votos json))))
      (is (= ["sim"] (mapv :voto (:votos json)))))
    (testing "o total e os numeros por opcao usam o MESMO conjunto: o voto escondido nao conta"
      (is (= 1 (:votos-total json)))
      (is (= {:sim 1 :nao 0 :abstencao 0} (:votos-por-opcao json))
          "helena votou nao (secreta + sem sessao) e abstencao (fechada): nada disso pode aparecer nos numeros"))
    (testing "o texto da resposta nao carrega nenhuma votacao escondida"
      (is (not-any? #(str/includes? (pr-str json) (str %)) [na-secreta na-fechada sem-sessao])))))

(deftest o-outro-vereador-tambem-e-filtrado
  (let [{:keys [ente]} (casa!)
        svc (servico #{ente})
        {:keys [json]} (get-json svc (str "/portal/casa/" ente "/vereadores/" rui))]
    (is (= 1 (:votos-total json)))
    (is (= {:sim 0 :nao 1 :abstencao 0} (:votos-por-opcao json))
        "rui: o 'sim' da secreta e a abstencao da fechada ficam fora; so' o 'nao' da publica")))

(deftest a-sessao-que-deixa-de-ser-publica-esconde-os-votos-ja-projetados
  ;; a regra e' da LEITURA, nao da projecao: fechar a sessao ao publico depois de os votos entrarem no read-model
  ;; os tira das saidas na proxima leitura, sem reprocessar evento.
  (let [ente (random-uuid)
        sessao (sessao! ente "ordinaria")
        vid (votacao-nominal! ente sessao "sim" "nao")
        _ (outbox/drenar! *ds* (consumers/registrar {}))
        svc (servico #{ente})
        csv #(:body (pt/response-for svc :get (str "/portal/casa/" ente "/dados-abertos/votos-nominais.csv")))]
    (is (str/includes? (csv) (str vid)) "enquanto a sessao e' publica, sai")
    (fechar-ao-publico! ente sessao)
    (is (not (str/includes? (csv) (str vid))) "fechada ao publico, some")))

(deftest outra-casa-nao-vaza-e-nao-e-escondida-pela-regra-da-primeira
  (let [a (casa!) b (casa!)
        svc (servico #{(:ente a) (:ente b)})
        ids-a (ids-do-csv (:body (pt/response-for svc :get (str "/portal/casa/" (:ente a) "/dados-abertos/votos-nominais.csv"))))
        ids-b (ids-do-csv (:body (pt/response-for svc :get (str "/portal/casa/" (:ente b) "/dados-abertos/votos-nominais.csv"))))]
    (is (= #{(str (:na-publica a))} ids-a) "o CSV da Casa A nao traz votacao da Casa B")
    (is (= #{(str (:na-publica b))} ids-b) "a Casa B continua publicando a sua votacao publica")
    (let [pa (:json (get-json svc (str "/portal/casa/" (:ente a) "/vereadores/" helena)))]
      (is (= [(str (:na-publica a))] (mapv :votacao-id (:votos pa)))
          "o perfil na Casa A so' mostra voto da Casa A (mesmo vereador-id nas duas Casas)"))))

(defn- anular! [ente votacao-id]
  (let [lv (tenancy/com-tenant* *ds* ente
             (fn [tx] (:votacoes/lock_version
                       (jdbc/execute-one! tx ["SELECT lock_version FROM legislativo.votacoes WHERE ente_id = ? AND id = ?"
                                              ente votacao-id]))))]
    (repo-leg/anular-votacao! *leg* ente {:id votacao-id :lock-version lv :updated-by (random-uuid)})))

(deftest o-voto-de-votacao-anulada-em-sessao-publica-nao-sai
  ;; ledger docs/16: a anulacao desfaz a votacao (correcao = nova votacao). O voto ja' projetado nao pode seguir no
  ;; CSV, no catalogo nem no perfil. Regra da LEITURA: anular depois da projecao esconde na proxima leitura.
  (let [ente (random-uuid)
        sessao (sessao! ente "ordinaria")
        valida (votacao-nominal! ente sessao "sim" "nao")
        anulada (votacao-nominal! ente sessao "nao" "sim")
        _ (outbox/drenar! *ds* (consumers/registrar {}))
        svc (servico #{ente})
        csv #(:body (pt/response-for svc :get (str "/portal/casa/" ente "/dados-abertos/votos-nominais.csv")))]
    (testing "controle positivo: antes de anular, as duas votacoes saem"
      (is (= #{(str valida) (str anulada)} (ids-do-csv (csv)))))
    (anular! ente anulada)
    (is (= 4 (linhas-projetadas ente)) "a anulacao nao apaga o read-model: quem esconde e' a leitura")
    (testing "o CSV so' tem a votacao valida"
      (is (= #{(str valida)} (ids-do-csv (csv)))))
    (testing "o catalogo conta so' as 2 linhas da valida"
      (let [por-chave (into {} (map (juxt :chave identity))
                            (:datasets (:json (get-json svc (str "/portal/casa/" ente "/dados-abertos")))))]
        (is (= 2 (get-in por-chave ["votos-nominais" :linhas])))))
    (testing "o perfil nao lista nem conta o voto anulado"
      (let [{:keys [json]} (get-json svc (str "/portal/casa/" ente "/vereadores/" helena))]
        (is (= [(str valida)] (mapv :votacao-id (:votos json))))
        (is (= 1 (:votos-total json)))
        (is (= {:sim 1 :nao 0 :abstencao 0} (:votos-por-opcao json))
            "helena votou nao na anulada: nao pode aparecer nos numeros")))))
