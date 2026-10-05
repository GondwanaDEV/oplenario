(ns oplenario.transparencia.votacoes-publicas-test
  "INTEGRACAO (PG real + borda Pedestal) — o PORTAL DE VOTACOES (frente 'portal-votacoes-publicas'): a lista das
  votacoes ENCERRADAS de sessoes PUBLICAS e o detalhe com o voto de cada vereador quando a votacao foi nominal.
  Repos REAIS de sessoes, legislativo e transparencia; o host (`oplenario.votacoes-publicas`) cruza sessoes e
  legislativo. As regras que nao podem falhar, uma a uma: sessao secreta (ou fechada ao publico) nunca sai —
  nem na lista, nem no detalhe, nem o voto por vereador; so' votacao encerrada (nada em curso, nada anulado, nada
  fora de plenario); isolamento por Casa; total e paginacao explicitos; voto secreto sem vereador."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
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
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.legislativo.components.repositorio-votacao-publica :as repo-vp]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.migracao :as migracao]
            [oplenario.rotas :as rotas]
            [oplenario.sessoes.components.repositorio :as repo-s]
            [oplenario.sessoes.components.repositorio-sessao-publica :as repo-sp]
            [oplenario.transparencia.components.repositorio :as transparencia-repo]
            [oplenario.transparencia.db.materia :as db-materia]
            [oplenario.transparencia.diplomat.http.in :as transparencia-http]
            [oplenario.votacoes-publicas :as votacoes-publicas])
  (:import (java.time Instant)))

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

(defn- servico [casas nomes]
  (-> (http/servico (config/carregar)
                    (transparencia-http/rotas
                     {:repo-transparencia *tra* :auth {:name ::sem-auth :enter identity}
                      :resolver-ente-publico #(or (parse-uuid (str %)) (throw (ex-info "x" {:tipo :validacao/invalido})))
                      :info-ente #(when (contains? casas %) {:nome-oficial "Câmara"})
                      :nomes-dos-vereadores nomes
                      :votacoes-publicas (fn [ente limite deslocamento materia]
                                           (votacoes-publicas/listar *ses* *leg* ente limite deslocamento materia))
                      :votacao-publica (fn [ente id] (votacoes-publicas/buscar *ses* *leg* ente id))})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- get! [svc url]
  (let [r (pt/response-for svc :get url)]
    {:status (:status r)
     :corpo (when (seq (:body r)) (json/read-value (:body r) json/keyword-keys-object-mapper))}))

(defn- sessao! [ente tipo]
  (:id (repo-s/agendar-sessao! *ses* ente {:id (random-uuid) :sessao-legislativa-id (random-uuid) :tipo-sessao tipo
                                           :modalidade "presencial" :agendada-para (Instant/parse "2026-09-10T12:00:00Z")})))

(defn- fechar-ao-publico! [ente sessao-id]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (jdbc/execute! tx ["UPDATE sessoes.sessao SET transmite_publica = false WHERE ente_id = ? AND id = ?"
                                ente sessao-id]))))

(defn- materia! [ente id numero ementa]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (db-materia/inserir! tx {:ente-id ente :proposicao-id id :tipo "projeto_lei" :ano 2026 :sequencial numero
                                      :urn-lex (str "urn:lex:br;x:2026;" numero) :ementa ementa :estado "em_comissoes"}))))

(defn- abrir! [ente sessao-id objeto-tipo objeto-id modalidade]
  (let [vid (random-uuid)]
    (repo-leg/abrir-votacao! *leg* ente (cond-> {:id vid :objeto-tipo objeto-tipo :objeto-id objeto-id
                                                 :modalidade modalidade :quorum-tipo "maioria_simples"}
                                          sessao-id (assoc :sessao-id sessao-id)))
    vid))

(defn- votar! [ente vid vereador voto]
  (repo-leg/registrar-voto! *leg* ente {:id (random-uuid) :votacao-id vid :vereador-id vereador :voto voto}))

(defn- encerrar! [ente vid & [extra]]
  (repo-leg/encerrar-votacao! *leg* ente (merge {:id vid :base-membros 3 :updated-by nil :lock-version 0} extra)))

(defn- nominal-encerrada!
  "Uma votacao NOMINAL encerrada de 3 votos (2 sim, 1 nao)."
  [ente sessao-id materia v1 v2 v3]
  (let [vid (abrir! ente sessao-id "proposicao" materia "nominal")]
    (votar! ente vid v1 "sim") (votar! ente vid v2 "nao") (votar! ente vid v3 "sim")
    (encerrar! ente vid)
    vid))

(def v1 (random-uuid))
(def v2 (random-uuid))
(def v3 (random-uuid))
(def nomes {v1 "Helena Past" v2 "Rui Nogueira" v3 "Ana Lima"})

(defn- casa!
  "Uma Casa com o cenario inteiro. Devolve os ids que os testes usam."
  []
  (let [ente (random-uuid)
        p1 (random-uuid)
        publica (sessao! ente "ordinaria")
        secreta (sessao! ente "secreta")
        fechada (sessao! ente "ordinaria")
        _ (fechar-ao-publico! ente fechada)
        _ (materia! ente p1 7 "Hortas comunitárias")
        nominal (nominal-encerrada! ente publica p1 v1 v2 v3)
        ;; secreta numa sessao PUBLICA: o resultado agregado e' publico, o voto individual nao existe
        secreta-em-publica (let [vid (abrir! ente publica "proposicao" p1 "secreta")]
                             (doseq [voto ["sim" "sim" "nao"]]
                               (repo-leg/registrar-voto-secreto! *leg* ente {:id (random-uuid) :votacao-id vid :voto voto}))
                             (encerrar! ente vid)
                             vid)
        simbolica (let [vid (abrir! ente publica "parecer" (random-uuid) "simbolica")]
                    (encerrar! ente vid {:resultado "aprovada"})
                    vid)
        ;; as que NUNCA podem sair
        aberta (abrir! ente publica "proposicao" p1 "nominal")
        anulada (let [vid (abrir! ente publica "proposicao" p1 "nominal")]
                  (repo-leg/anular-votacao! *leg* ente {:id vid :updated-by nil :lock-version 0})
                  vid)
        sem-sessao (let [vid (abrir! ente nil "proposicao" p1 "nominal")]
                     (encerrar! ente vid)
                     vid)
        na-secreta (nominal-encerrada! ente secreta p1 v1 v2 v3)
        na-fechada (nominal-encerrada! ente fechada p1 v1 v2 v3)]
    {:ente ente :p1 p1 :publica publica :nominal nominal :secreta-em-publica secreta-em-publica
     :simbolica simbolica :aberta aberta :anulada anulada :sem-sessao sem-sessao
     :na-secreta na-secreta :na-fechada na-fechada}))

(defn- ids-da-lista [corpo] (set (map :votacao-id (:votacoes corpo))))

;; ---------- as regras ----------

(deftest controle-positivo-o-que-o-portal-esconde-existe-de-verdade
  ;; sem isto, "nao apareceu" valeria tambem para "nunca foi criado". As votacoes abaixo ESTAO encerradas no
  ;; legislativo, com voto por vereador; e' a sessao delas que o portal nao mostra.
  (let [{:keys [ente publica secreta-em-publica na-secreta na-fechada]} (casa!)]
    (doseq [vid [na-secreta na-fechada]]
      (let [v (repo-vp/votacao-encerrada *leg* ente vid)]
        (is (= 3 (count (:votos v))) "a votacao existe, encerrada, com os 3 votos")
        (is (nil? (repo-sp/sessao-publica *ses* ente (:sessao-id v))) "e a sessao dela nao e' publica")))
    (is (some? (repo-sp/sessao-publica *ses* ente publica)) "a sessao publica, o portal enxerga")
    (is (= [publica] (mapv :id (repo-sp/sessoes-publicas *ses* ente)))
        "das 3 sessoes da Casa (publica, secreta, fechada) so' a publica entra na lista")
    (is (some? (repo-vp/votacao-encerrada *leg* ente secreta-em-publica)))))

(deftest a-lista-so-tem-votacao-encerrada-de-sessao-publica
  (let [{:keys [ente nominal secreta-em-publica simbolica aberta anulada sem-sessao na-secreta na-fechada]} (casa!)
        svc (servico #{ente} (constantly nomes))
        {:keys [status corpo]} (get! svc (str "/portal/casa/" ente "/votacoes"))
        ids (ids-da-lista corpo)]
    (is (= 200 status))
    (testing "as tres encerradas de sessao publica (nominal, secreta, simbolica) estao na lista"
      (is (= (set (map str [nominal secreta-em-publica simbolica])) ids)))
    (testing "SESSAO SECRETA: a votacao nominal de la' nao aparece"
      (is (not (contains? ids (str na-secreta)))))
    (testing "sessao fechada ao publico (transmissao nao publica): idem"
      (is (not (contains? ids (str na-fechada)))))
    (testing "em curso, anulada e fora de plenario nunca saem"
      (is (not-any? #(contains? ids (str %)) [aberta anulada sem-sessao])))
    (testing "o total diz quantas existem, sem corte"
      (is (= 3 (:total corpo)))
      (is (= [1 20] [(:pagina corpo) (:por-pagina corpo)])))))

(deftest o-item-traz-data-materia-resultado-e-placar
  (let [{:keys [ente p1 nominal secreta-em-publica simbolica]} (casa!)
        svc (servico #{ente} (constantly nomes))
        por-id (into {} (map (juxt :votacao-id identity))
                     (:votacoes (:corpo (get! svc (str "/portal/casa/" ente "/votacoes")))))
        n (get por-id (str nominal))]
    (testing "votacao nominal: a sessao, a materia (link da ficha), o resultado e o placar"
      (is (= "ordinaria" (get-in n [:sessao :tipo-sessao])))
      (is (= "2026-09-10T12:00:00Z" (get-in n [:sessao :data])) "a data da sessao (agendamento, sem abertura)")
      (is (= {:proposicao-id (str p1) :tipo "projeto_lei" :sequencial 7 :ano 2026 :ementa "Hortas comunitárias"}
             (:materia n)))
      (is (= ["nominal" "aprovada"] ((juxt :modalidade :resultado) n)))
      (is (= {:sim 2 :nao 1 :abstencoes 0 :base-membros 3} (:placar n)))
      (is (string? (:encerrada-em n))))
    (testing "voto secreto: o resultado agregado e' publico; a lista nao carrega voto de ninguem"
      (let [s (get por-id (str secreta-em-publica))]
        (is (= "secreta" (:modalidade s)))
        (is (= {:sim 2 :nao 1 :abstencoes 0 :base-membros 3} (:placar s)))
        (is (not (contains? s :votos)))))
    (testing "simbolica (aclamacao): sem placar; objeto que nao e' materia vem sem materia"
      (let [a (get por-id (str simbolica))]
        (is (nil? (:placar a)))
        (is (nil? (:materia a)))
        (is (= "parecer" (:objeto-tipo a)))))))

(deftest o-detalhe-nominal-traz-o-voto-de-cada-vereador-pelo-nome
  (let [{:keys [ente nominal]} (casa!)
        svc (servico #{ente} (constantly nomes))
        {:keys [status corpo]} (get! svc (str "/portal/casa/" ente "/votacoes/" nominal))]
    (is (= 200 status))
    (is (= [{:vereador "Ana Lima" :voto "sim"} {:vereador "Helena Past" :voto "sim"} {:vereador "Rui Nogueira" :voto "nao"}]
           (mapv #(select-keys % [:vereador :voto]) (:votos corpo)))
        "um por vereador, em ordem alfabetica, pelo nome parlamentar")
    (is (= (set (map str [v1 v2 v3])) (set (map :vereador-id (:votos corpo)))))
    (is (= {:sim 2 :nao 1 :abstencoes 0 :base-membros 3} (:placar corpo)))))

(deftest sem-ranking-nem-percentual-de-vereador
  (let [{:keys [ente nominal]} (casa!)
        svc (servico #{ente} (constantly nomes))
        texto (str (:corpo (get! svc (str "/portal/casa/" ente "/votacoes/" nominal)))
                   (:corpo (get! svc (str "/portal/casa/" ente "/votacoes"))))]
    (is (not (re-find #"(?i)fidelidade|ranking|percent|alinhamento" texto)))))

(deftest o-detalhe-de-voto-secreto-ou-simbolica-nao-tem-voto-por-vereador
  (let [{:keys [ente secreta-em-publica simbolica]} (casa!)
        svc (servico #{ente} (constantly nomes))]
    (doseq [vid [secreta-em-publica simbolica]]
      (let [{:keys [status corpo]} (get! svc (str "/portal/casa/" ente "/votacoes/" vid))]
        (is (= 200 status))
        (is (= [] (:votos corpo)) "nem o nome de quem votou, nem a contagem por vereador")))))

(deftest o-detalhe-nega-o-que-nao-e-publico-com-um-404-so
  (let [{:keys [ente aberta anulada sem-sessao na-secreta na-fechada]} (casa!)
        svc (servico #{ente} (constantly nomes))]
    (testing "sessao secreta: nem a votacao nem o voto por vereador"
      (let [r (get! svc (str "/portal/casa/" ente "/votacoes/" na-secreta))]
        (is (= 404 (:status r)))
        (is (not (re-find #"(?i)Helena|Rui|Ana Lima" (str (:corpo r)))))))
    (testing "fechada ao publico, em curso, anulada, fora de plenario e inexistente: o mesmo 404"
      (is (= #{404} (set (map #(:status (get! svc (str "/portal/casa/" ente "/votacoes/" %)))
                              [na-fechada aberta anulada sem-sessao (random-uuid)])))))
    (testing "id malformado e' 400, nunca 404"
      (is (= 400 (:status (get! svc (str "/portal/casa/" ente "/votacoes/nao-e-uuid"))))))))

(deftest isolamento-por-casa
  (let [a (casa!) b (casa!)
        svc (servico #{(:ente a) (:ente b)} (constantly nomes))
        lista-a (:corpo (get! svc (str "/portal/casa/" (:ente a) "/votacoes")))]
    (is (empty? (filter #(contains? (ids-da-lista lista-a) (str %)) [(:nominal b) (:simbolica b)]))
        "a lista de uma Casa nao traz votacao da outra")
    (is (= 404 (:status (get! svc (str "/portal/casa/" (:ente a) "/votacoes/" (:nominal b)))))
        "o detalhe de uma votacao da Casa B pelo caminho da Casa A e' 404")
    (is (= 200 (:status (get! svc (str "/portal/casa/" (:ente b) "/votacoes/" (:nominal b))))))))

(deftest casa-inexistente-e-ente-malformado
  (let [{:keys [ente nominal]} (casa!)
        svc (servico #{ente} (constantly nomes))
        outra (random-uuid)]
    (is (= [404 404] [(:status (get! svc (str "/portal/casa/" outra "/votacoes")))
                      (:status (get! svc (str "/portal/casa/" outra "/votacoes/" nominal)))]))
    (is (= 400 (:status (get! svc "/portal/casa/nao-e-uuid/votacoes"))))))

(deftest paginacao-explicita-sem-cortar-em-silencio
  (let [ente (random-uuid)
        publica (sessao! ente "ordinaria")
        _ (dotimes [_ 23] (let [vid (abrir! ente publica "requerimento" (random-uuid) "simbolica")]
                            (encerrar! ente vid {:resultado "aprovada"})))
        svc (servico #{ente} (constantly {}))
        url #(str "/portal/casa/" ente "/votacoes" %)
        p1 (:corpo (get! svc (url "")))
        p2 (:corpo (get! svc (url "?pagina=2")))
        p3 (:corpo (get! svc (url "?pagina=3")))]
    (is (= [23 20 3 0] [(:total p1) (count (:votacoes p1)) (count (:votacoes p2)) (count (:votacoes p3))]))
    (is (= [1 2 3] [(:pagina p1) (:pagina p2) (:pagina p3)]))
    (is (= 23 (count (into (ids-da-lista p1) (ids-da-lista p2))))
        "as duas paginas juntas sao as 23, sem repetir nem perder (ordem estavel)")
    (is (= 23 (:total p3)) "pagina alem do fim: lista vazia, o total continua dito")
    (testing "pagina invalida e' 400, nunca vira a pagina 1 em silencio"
      (is (= [400 400 400] (mapv #(:status (get! svc (url %))) ["?pagina=0" "?pagina=abc" "?pagina=1&pagina=2"]))))))

(deftest o-host-liga-a-rota-de-ponta-a-ponta
  ;; as outras provas montam so' as rotas de transparencia; esta passa por `rotas/montar` (a composicao de producao):
  ;; sessoes + legislativo + transparencia reais, e os seams `votacoes-publicas`/`votacao-publica` do host.
  (let [{:keys [ente nominal na-secreta]} (casa!)
        svc (-> (http/servico (config/carregar)
                              (rotas/montar {:idp (idp-dev/idp-dev) :repo-legislativo *leg* :repo-sessoes *ses*
                                             :repo-transparencia *tra*
                                             :repo-cadastros
                                             #_{:clj-kondo/ignore [:missing-protocol-method]}
                                             (reify repo-cadastros-comp/RepoCadastros
                                               (listar-vereadores [_ _ _]
                                                 (mapv (fn [[id nome]] {:id id :nome nome}) nomes)))
                                             :info-ente #(when (= % ente) {:nome-oficial "Câmara"})})
                              it/globais)
                ph/create-server ::ph/service-fn)
        lista (get! svc (str "/portal/casa/" ente "/votacoes"))]
    (is (= 200 (:status lista)))
    (is (= 3 (:total (:corpo lista))))
    (let [d (get! svc (str "/portal/casa/" ente "/votacoes/" nominal))]
      (is (= 200 (:status d)))
      (is (= ["Ana Lima" "Helena Past" "Rui Nogueira"] (mapv :vereador (:votos (:corpo d))))
          "o nome vem do seam `nomes-dos-vereadores` do host (cadastros)"))
    (is (= 404 (:status (get! svc (str "/portal/casa/" ente "/votacoes/" na-secreta))))
        "pela composicao de producao, a sessao secreta tambem nao sai")))

(deftest a-mais-recente-vem-primeiro
  (let [ente (random-uuid)
        publica (sessao! ente "ordinaria")
        a (abrir! ente publica "requerimento" (random-uuid) "simbolica")
        _ (encerrar! ente a {:resultado "aprovada"})
        _ (Thread/sleep 5)
        b (abrir! ente publica "requerimento" (random-uuid) "simbolica")
        _ (encerrar! ente b {:resultado "rejeitada"})
        svc (servico #{ente} (constantly {}))
        ids (mapv :votacao-id (:votacoes (:corpo (get! svc (str "/portal/casa/" ente "/votacoes")))))]
    (is (= [(str b) (str a)] ids))))

;; ---------- o filtro por materia (a ficha publica liga as votacoes dela) ----------

(deftest o-filtro-por-materia-so-restringe-e-nunca-vaza-sessao-secreta
  (let [{:keys [ente p1 publica nominal secreta-em-publica simbolica na-secreta na-fechada aberta anulada sem-sessao]}
        (casa!)
        redacao (let [vid (abrir! ente publica "redacao_final" p1 "simbolica")]
                  (encerrar! ente vid {:resultado "aprovada"})
                  vid)
        ;; um parecer cujo objeto_id COLIDE com o id da materia (sem FK no banco): tem de ficar de fora
        parecer-colide (let [vid (abrir! ente publica "parecer" p1 "simbolica")]
                         (encerrar! ente vid {:resultado "aprovada"})
                         vid)
        svc (servico #{ente} (constantly nomes))
        lista (:corpo (get! svc (str "/portal/casa/" ente "/votacoes?materia=" p1)))
        ids (ids-da-lista lista)]
    (testing "so' as votacoes encerradas da proposicao em sessao publica: a nominal, a secreta (so' o resultado) e a redacao final"
      (is (= (set (map str [nominal secreta-em-publica redacao])) ids))
      (is (= 3 (:total lista)) "o total e' do mesmo filtro"))
    (testing "SESSAO SECRETA ou fechada ao publico: a votacao dessa MESMA materia continua escondida"
      (is (not-any? #(contains? ids (str %)) [na-secreta na-fechada])))
    (testing "em curso, anulada e fora de plenario continuam fora"
      (is (not-any? #(contains? ids (str %)) [aberta anulada sem-sessao])))
    (testing "votacao de outro objeto (parecer) nao entra, nem com o objeto_id igual ao da materia"
      (is (not-any? #(contains? ids (str %)) [simbolica parecer-colide])))
    (testing "o filtro so' restringe: sem ele a lista traz mais do que com ele"
      (let [toda (:corpo (get! svc (str "/portal/casa/" ente "/votacoes")))]
        (is (< (:total lista) (:total toda)))
        (is (every? (ids-da-lista toda) ids))))))

(deftest o-filtro-por-materia-segue-o-isolamento-por-casa
  (let [a (casa!) b (casa!)
        svc (servico #{(:ente a) (:ente b)} (constantly nomes))
        url #(str "/portal/casa/" %1 "/votacoes?materia=" %2)]
    (testing "a materia da Casa B perguntada pela Casa A: nada (nem 404, nem a votacao de B)"
      (let [r (get! svc (url (:ente a) (:p1 b)))]
        (is (= 200 (:status r)))
        (is (= [0 []] [(:total (:corpo r)) (:votacoes (:corpo r))]))))
    (testing "cada Casa ve as proprias"
      (is (contains? (ids-da-lista (:corpo (get! svc (url (:ente b) (:p1 b))))) (str (:nominal b)))))
    (testing "materia sem votacao publica: lista vazia com total 0"
      (let [r (:corpo (get! svc (url (:ente a) (random-uuid))))]
        (is (= [0 []] [(:total r) (:votacoes r)]))))))

(deftest o-filtro-por-materia-pagina-sem-repetir-nem-pular
  (let [ente (random-uuid)
        publica (sessao! ente "ordinaria")
        materia (random-uuid)
        outra (random-uuid)
        feitas (vec (for [_ (range 23)]
                      (let [vid (abrir! ente publica "proposicao" materia "simbolica")]
                        (encerrar! ente vid {:resultado "aprovada"})
                        vid)))
        _ (dotimes [_ 4] (let [vid (abrir! ente publica "proposicao" outra "simbolica")]
                           (encerrar! ente vid {:resultado "aprovada"})))
        svc (servico #{ente} (constantly {}))
        url #(str "/portal/casa/" ente "/votacoes?materia=" materia %)
        p1 (:corpo (get! svc (url "")))
        p2 (:corpo (get! svc (url "&pagina=2")))]
    (is (= [23 20 3] [(:total p1) (count (:votacoes p1)) (count (:votacoes p2))]))
    (is (= (set (map str feitas)) (into (ids-da-lista p1) (ids-da-lista p2))) "as 23 da materia, cada uma uma vez")
    (is (= [2 20] [(:pagina p2) (:por-pagina p2)]))))

(deftest materia-malformada-ou-repetida-e-400-nunca-lista-inteira
  (let [{:keys [ente p1]} (casa!)
        svc (servico #{ente} (constantly nomes))
        url #(str "/portal/casa/" ente "/votacoes" %)]
    (is (= [400 400] [(:status (get! svc (url "?materia=nao-e-uuid")))
                      (:status (get! svc (url (str "?materia=" p1 "&materia=" (random-uuid)))))]))
    (is (= 200 (:status (get! svc (url "?materia=")))) "valor vazio = sem filtro")))
