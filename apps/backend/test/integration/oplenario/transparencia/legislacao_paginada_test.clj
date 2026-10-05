(ns oplenario.transparencia.legislacao-paginada-test
  "INTEGRACAO (PG real + borda Pedestal) — a lista de leis do portal PAGINADA. Repo REAL de transparencia sob FORCE
  RLS; as normas entram direto no read-model (a projecao ja' e' provada em portal_test; aqui importa a LEITURA).
  Regras que nao podem falhar: a paginacao nao repete nem pula norma entre paginas (ordem estavel mesmo com empate
  total); o total e' do mesmo filtro e sai sem pagina; os filtros tipo/ano/numero valem na pagina; isolamento por
  Casa."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.transparencia.components.repositorio :as transparencia-repo]
            [oplenario.transparencia.db.norma :as db-norma]
            [oplenario.transparencia.diplomat.http.in :as transparencia-http])
  (:import (java.time Instant)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *tra* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)
                *tra* (transparencia-repo/->RepoTransparenciaPg c)]
        (try (t) (finally (component/stop c)))))))

(defn- servico [casas]
  (-> (http/servico (config/carregar)
                    (transparencia-http/rotas
                     {:repo-transparencia *tra* :auth {:name ::sem-auth :enter identity}
                      :resolver-ente-publico #(or (parse-uuid (str %)) (throw (ex-info "x" {:tipo :validacao/invalido})))
                      :info-ente #(when (contains? casas %) {:nome-oficial "Câmara"})})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- get! [svc url]
  (let [r (pt/response-for svc :get url)]
    {:status (:status r)
     :corpo (when (seq (:body r)) (json/read-value (:body r) json/keyword-keys-object-mapper))}))

(defn- norma!
  "Uma norma publicada direto no read-model. `:publicado-em` fixo por padrao: o empate TOTAL em todos os campos de
  ordem e' o caso que so' o desempate por id resolve."
  [ente {:keys [tipo-norma numero ano publicado-em proposicao-id norma-id]
         :or {tipo-norma "lei" ano 2026 publicado-em (Instant/parse "2026-03-10T15:00:00Z")}}]
  (let [nid (or norma-id (random-uuid))]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (db-norma/inserir! tx {:ente-id ente :norma-id nid :proposicao-id (or proposicao-id (random-uuid))
                               :tipo-norma tipo-norma :numero numero :ano ano
                               :urn (str "urn:lex:br;ce;x:" tipo-norma ":" ano ";" numero)
                               :ementa (str tipo-norma " " numero "/" ano)
                               :publicado-em publicado-em :veiculo-publicacao "Diario Oficial do Municipio"})))
    nid))

(defn- ids [corpo] (mapv :norma-id (:normas corpo)))
(defn- url [ente & [qs]] (str "/portal/casa/" ente "/legislacao" qs))

(defn- casa-com
  "Uma Casa com `n` normas, todas com o MESMO publicado_em (empate total): [ente [norma-ids]]."
  [n]
  (let [ente (random-uuid)]
    [ente (vec (for [i (range n)] (norma! ente {:numero (inc i)})))]))

(deftest a-lista-pagina-sem-repetir-nem-pular-norma
  (let [[ente todas] (casa-com 45)
        svc (servico #{ente})
        p1 (:corpo (get! svc (url ente)))
        p2 (:corpo (get! svc (url ente "?pagina=2")))
        p3 (:corpo (get! svc (url ente "?pagina=3")))
        p4 (:corpo (get! svc (url ente "?pagina=4")))]
    (testing "20 + 20 + 5 + 0, com o total e o tamanho da pagina ditos em toda resposta"
      (is (= [20 20 5 0] (mapv (comp count :normas) [p1 p2 p3 p4])))
      (is (= [45 45 45 45] (mapv :normas-total [p1 p2 p3 p4])) "pagina alem do fim: vazia, o total continua dito")
      (is (= [1 2 3 4] (mapv :pagina [p1 p2 p3 p4])))
      (is (= [20 20 20 20] (mapv :por-pagina [p1 p2 p3 p4]))))
    (testing "as paginas juntas sao as 45 normas, cada uma UMA vez (todas empatam em publicado_em)"
      (let [vistas (concat (ids p1) (ids p2) (ids p3))]
        (is (= 45 (count vistas)))
        (is (= (set (map str todas)) (set vistas)))))
    (testing "a ordem e' a mesma em cargas repetidas (desempate por id, nunca por acaso do plano)"
      (is (= (ids p2) (ids (:corpo (get! svc (url ente "?pagina=2"))))))
      (is (= (vec (sort (comp - compare) (map str todas)))
             (vec (concat (ids p1) (ids p2) (ids p3))))
          "empate total em publicado_em: id decrescente, de ponta a ponta"))))

(deftest o-filtro-vale-na-pagina-e-o-total-e-do-mesmo-filtro
  (let [ente (random-uuid)
        leis (vec (for [i (range 23)] (norma! ente {:numero (inc i) :ano 2026})))
        _ (dotimes [i 4] (norma! ente {:tipo-norma "resolucao" :numero (inc i) :ano 2026}))
        _ (dotimes [i 3] (norma! ente {:numero (inc i) :ano 2025}))
        svc (servico #{ente})
        p1 (:corpo (get! svc (url ente "?tipo=lei&ano=2026")))
        p2 (:corpo (get! svc (url ente "?tipo=lei&ano=2026&pagina=2")))]
    (is (= [20 3] [(count (:normas p1)) (count (:normas p2))]))
    (is (= [23 23] [(:normas-total p1) (:normas-total p2)]) "o total e' o do filtro, nao o do acervo (30)")
    (is (= (set (map str leis)) (set (concat (ids p1) (ids p2)))) "so' as 23 leis de 2026, sem repetir")
    (is (every? #(= ["lei" 2026] ((juxt :tipo-norma :ano) %)) (concat (:normas p1) (:normas p2))))
    (testing "filtrado, a ordem e' (ano desc, numero desc): a pagina 1 tem os numeros 23..4"
      (is (= (vec (range 23 3 -1)) (mapv :numero (:normas p1)))))
    (testing "o filtro por numero ve a mesma Casa inteira, sem teto de pagina escondendo a norma"
      (let [r (:corpo (get! svc (url ente "?numero=1&tipo=resolucao")))]
        (is (= [1 1] [(count (:normas r)) (:normas-total r)]))))))

(deftest pagina-invalida-e-400
  (let [[ente _] (casa-com 2)
        svc (servico #{ente})]
    (is (= [400 400 400 400]
           (mapv #(:status (get! svc (url ente %))) ["?pagina=0" "?pagina=-1" "?pagina=abc" "?pagina=1&pagina=2"]))
        "nunca vira a pagina 1 em silencio")
    (is (= 200 (:status (get! svc (url ente "?pagina=99999999")))) "pagina enorme: lista vazia, nao 500")))

(deftest isolamento-por-casa
  (let [[a todas-a] (casa-com 25)
        [b todas-b] (casa-com 3)
        svc (servico #{a b})
        da-a (concat (ids (:corpo (get! svc (url a)))) (ids (:corpo (get! svc (url a "?pagina=2")))))
        da-b (:corpo (get! svc (url b)))]
    (is (= 25 (count da-a)))
    (is (empty? (filter (set (map str todas-b)) da-a)) "a lista de uma Casa nao traz norma da outra")
    (is (= [3 3] [(count (:normas da-b)) (:normas-total da-b)]))
    (is (= (set (map str todas-b)) (set (ids da-b))))
    (is (= 25 (count (set (map str todas-a)))))))
