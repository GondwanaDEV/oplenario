(ns oplenario.transparencia.dados-abertos-test
  "INTEGRACAO (PG real + borda Pedestal) — os DADOS ABERTOS do portal (Onda E, `dados-abertos`): o catalogo conta as
  linhas e a ultima atualizacao do read-model de CADA Casa, o CSV traz o dataset inteiro (com o nome do vereador
  vindo do seam do host), anonimo, e Casa inexistente ou arquivo fora do catalogo e' 404. Voto nominal dado em
  sessao que o portal nao mostra (secreta ou fechada ao publico) nao entra no arquivo nem na contagem."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
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
            [oplenario.transparencia.db.materia :as db-materia]
            [oplenario.transparencia.db.norma :as db-norma]
            [oplenario.transparencia.db.parlamentar :as db-parlamentar]
            [oplenario.transparencia.diplomat.http.in :as transparencia-http])
  (:import (java.time Instant)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c) *repo* (transparencia-repo/->RepoTransparenciaPg c)]
        (try (t) (finally (component/stop c)))))))

(def ^:private sessoes-publicas
  "As sessoes que o host declara publicas (o seam `sessoes-publicas`). Um conjunto so' para todas as Casas: o recorte
  por Casa ja' vem do ente_id e da RLS."
  (atom #{}))

(defn- casa!
  "Uma Casa com 2 proposicoes (uma com virgula e quebra na ementa), 1 norma e 2 votos nominais de 2 vereadores numa
  sessao publica — mais 1 voto nominal numa sessao SECRETA, que o portal nao pode mostrar."
  []
  (let [ente (random-uuid) p1 (random-uuid) p2 (random-uuid) v1 (random-uuid) v2 (random-uuid) vt (random-uuid)
        publica (random-uuid) secreta (random-uuid)]
    (swap! sessoes-publicas conj publica)
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (db-materia/inserir! tx {:ente-id ente :proposicao-id p1 :tipo "projeto_lei" :ano 2026 :sequencial 7
                                 :urn-lex "urn:lex:br;x:2026;7" :ementa "Hortas comunitárias, em terrenos\npúblicos"
                                 :autor-tipo "vereador" :autor-texto "Helena Past" :estado "em_comissoes"})
        (db-materia/inserir! tx {:ente-id ente :proposicao-id p2 :tipo "projeto_lei" :ano 2026 :sequencial 3
                                 :urn-lex "urn:lex:br;x:2026;3" :ementa "Semana da água" :estado "aprovada"})
        (db-norma/inserir! tx {:ente-id ente :norma-id (random-uuid) :proposicao-id p2 :tipo-norma "lei" :numero 101
                               :ano 2026 :urn "urn:lex:br;x:lei:2026;101" :ementa "Institui a Semana da água"
                               :publicado-em (Instant/parse "2026-09-01T12:00:00Z") :veiculo-publicacao "DOM"})
        (doseq [[v voto] [[v1 "sim"] [v2 "nao"]]]
          (db-parlamentar/registrar-voto! tx {:ente-id ente :votacao-id vt :sessao-id publica :vereador-id v
                                              :proposicao-id p2 :voto voto
                                              :ocorrido-em (Instant/parse "2026-08-20T17:00:00Z")}))
        (db-parlamentar/registrar-voto! tx {:ente-id ente :votacao-id (random-uuid) :sessao-id secreta :vereador-id v1
                                            :proposicao-id p1 :voto "abstencao"
                                            :ocorrido-em (Instant/parse "2026-08-21T17:00:00Z")})))
    {:ente ente :p1 p1 :v1 v1 :v2 v2}))

(defn- servico
  ([casas nomes] (servico casas nomes (fn [_] @sessoes-publicas)))
  ([casas nomes publicas]
   (-> (http/servico (config/carregar)
                     (transparencia-http/rotas (cond-> {:repo-transparencia *repo* :auth {:name ::sem-auth :enter identity}
                                                        :resolver-ente-publico #(or (parse-uuid (str %))
                                                                                    (throw (ex-info "x" {:tipo :validacao/invalido})))
                                                        :info-ente #(when (contains? casas %) {:nome-oficial "Câmara"})
                                                        :nomes-dos-vereadores nomes}
                                                 publicas (assoc :sessoes-publicas publicas)))
                     it/globais)
       ph/create-server ::ph/service-fn)))

(defn- ler [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(deftest o-catalogo-conta-cada-dataset-da-propria-casa
  (let [{:keys [ente]} (casa!)
        _outra (casa!)
        svc (servico #{ente} (constantly {}))
        r (pt/response-for svc :get (str "/portal/casa/" ente "/dados-abertos"))
        por-chave (into {} (map (juxt :chave identity)) (:datasets (ler r)))]
    (is (= 200 (:status r)) "anonimo: sem Authorization")
    (is (= {"proposicoes" 2 "legislacao" 1 "votos-nominais" 2} (update-vals por-chave :linhas))
        "as linhas desta Casa — a outra Casa, com o mesmo volume, nao soma (RLS); o voto da sessao secreta nao conta")
    (is (= "proposicoes.csv" (get-in por-chave ["proposicoes" :arquivo])))
    (is (string? (get-in por-chave ["legislacao" :atualizado-em])))
    (is (every? #(seq (:descricao %)) (get-in por-chave ["votos-nominais" :colunas])) "o dicionario de dados vai junto")))

(deftest o-csv-traz-o-dataset-inteiro-com-o-nome-do-vereador
  (let [{:keys [ente p1 v1 v2]} (casa!)
        svc (servico #{ente} (fn [e] (when (= e ente) {v1 "Helena Past" v2 "Rui Nogueira"})))]
    (testing "proposições: ordem estável (ano, tipo, número) e a ementa com vírgula e quebra entre aspas"
      (let [r (pt/response-for svc :get (str "/portal/casa/" ente "/dados-abertos/proposicoes.csv"))
            corpo (:body r)]
        (is (= 200 (:status r)))
        (is (str/starts-with? (get-in r [:headers "Content-Type"]) "text/csv"))
        (is (= "attachment; filename=\"proposicoes.csv\"" (get-in r [:headers "Content-Disposition"])))
        (is (< (str/index-of corpo ",3,2026,") (str/index-of corpo ",7,2026,")) "o nº 3 antes do nº 7")
        (is (str/includes? corpo (str p1 ",projeto_lei,7,2026,urn:lex:br;x:2026;7,\"Hortas comunitárias, em terrenos\npúblicos\",vereador,Helena Past,em_comissoes,")))))
    (testing "votos nominais: o nome vem do seam do host, o voto e a matéria da própria linha"
      (let [corpo (:body (pt/response-for svc :get (str "/portal/casa/" ente "/dados-abertos/votos-nominais.csv")))]
        (is (str/includes? corpo (str v1 ",Helena Past,sim\r\n")))
        (is (str/includes? corpo (str v2 ",Rui Nogueira,nao\r\n")))
        (is (str/includes? corpo ",projeto_lei 3/2026,"))))))

(deftest voto-nominal-de-sessao-que-o-portal-nao-mostra-nao-sai
  (let [{:keys [ente v1]} (casa!)
        nomes (fn [_] {v1 "Helena Past"})]
    (testing "o voto dado em sessao secreta fica fora do CSV: so' os dois da sessao publica"
      (let [corpo (:body (pt/response-for (servico #{ente} nomes)
                                          :get (str "/portal/casa/" ente "/dados-abertos/votos-nominais.csv")))]
        (is (not (str/includes? corpo "abstencao")) "o voto da sessao secreta era o unico 'abstencao'")
        (is (not (str/includes? corpo "projeto_lei 7/2026")) "nem a materia votada so' na sessao secreta")
        (is (= 3 (count (str/split-lines corpo))) "cabecalho + os dois votos da sessao publica")))
    (testing "sem o seam do host, nenhum voto sai (fail-closed) — o catalogo diz 0 e o arquivo so' tem o cabecalho"
      (let [svc (servico #{ente} nomes nil)
            cat (into {} (map (juxt :chave :linhas))
                      (:datasets (ler (pt/response-for svc :get (str "/portal/casa/" ente "/dados-abertos")))))
            corpo (:body (pt/response-for svc :get (str "/portal/casa/" ente "/dados-abertos/votos-nominais.csv")))]
        (is (= 0 (get cat "votos-nominais")))
        (is (= 1 (count (str/split-lines corpo))))))))

(deftest casa-inexistente-e-arquivo-fora-do-catalogo-sao-404
  (let [{:keys [ente]} (casa!)
        svc (servico #{ente} (constantly {}))
        outra (random-uuid)]
    (is (= [404 404] [(:status (pt/response-for svc :get (str "/portal/casa/" outra "/dados-abertos")))
                      (:status (pt/response-for svc :get (str "/portal/casa/" outra "/dados-abertos/proposicoes.csv")))]))
    (is (= 404 (:status (pt/response-for svc :get (str "/portal/casa/" ente "/dados-abertos/despesas.csv"))))
        "despesas nao e' dataset do portal (o dado fiscal e' do sistema contabil)")
    (is (= 404 (:status (pt/response-for svc :get (str "/portal/casa/" ente "/dados-abertos/..%2Fsegredo")))))
    (is (= 400 (:status (pt/response-for svc :get "/portal/casa/nao-e-uuid/dados-abertos"))))))
