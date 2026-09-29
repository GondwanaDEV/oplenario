(ns oplenario.auditoria.trilha-test
  "INTEGRACAO (PG real) — a TRILHA DE AUDITORIA da Casa (ADR-0017) atravessando a borda Pedestal com o interceptor
  entre os globais: a escrita e a negacao entram seladas, a leitura comum nao; cada papel ve o seu escopo; a corrente
  confere; o IP guardado inteiro sai truncado e so' ele pode ser anulado; o dia que fecha vira selo publicado e
  ancorado; a exportacao do auditor e' ela mesma registrada."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [oplenario.auditoria.components.repositorio :as repo]
            [oplenario.auditoria.diplomat.http.in :as auditoria-http]
            [oplenario.auditoria.logic :as logic]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.db-util :as comum]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao])
  (:import (java.sql Timestamp)
           (java.time Instant)
           (java.time.temporal ChronoUnit)))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- rp [] (repo/map->RepoAuditoriaPg {:datasource {:ds *ds*}}))

(def maria (random-uuid))   ; secretaria
(def rui (random-uuid))     ; vereador
(def ana (random-uuid))     ; auditora (controle interno)
(def beto (random-uuid))    ; admin_ente
(def cida (random-uuid))    ; cidada

(def papeis-de {maria #{"secretario"} rui #{"vereador"} ana #{"auditor"} beto #{"admin_ente"} cida #{}})

(defn- fake-identidade []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ iid] {:vinculo-ativo {:id (random-uuid) :tipo (if (= iid cida) "cidadao" "servidor")}
                              :papeis (papeis-de iid)})))

(def prop "30000000-0000-0000-0000-000000000003")

(defn- servico [ente ancoras]
  (let [auth (it/autenticacao (idp-dev/idp-dev) (fake-identidade))
        rotas (into #{["/materias/:proposicao-id/despachar" :post
                       [auth (fn [_] (assoc (http/json-resposta 201 {:ok true}) :auditoria {:rotulo "PL 7/2026" :campos [:relator]}))]
                       :route-name :legislativo/despachar]
                      ["/identidade/acessos" :post [auth (fn [_] (http/json-resposta 201 {:ok true}))]
                       :route-name :identidade/conceder-acesso]
                      ["/so-secretaria" :post [auth (it/exige-papel "secretario") (fn [_] (http/json-resposta 200 {}))]
                       :route-name :legislativo/so-secretaria]
                      ["/ler" :get [auth (fn [_] (http/json-resposta 200 {}))] :route-name :legislativo/ler]}
                    (auditoria-http/rotas {:auth auth :repo-auditoria (rp)
                                           :resolver-ente-publico #(parse-uuid (str %))
                                           :casa-existe? #{ente}
                                           :seams {:nome-de {maria "Maria Secretária" rui "Rui Vereador"
                                                             ana "Ana Auditora" beto "Beto Admin"}
                                                   :atuacao-da-operacao (fn [_ _] [{:em (Instant/now) :acao "casa-provisionada"
                                                                                  :operador-nome "Rafaela" :selo "ab"}])}}))]
    (-> (http/servico (config/carregar) rotas
                      (it/globais-com [(auditoria-http/interceptor (rp) {:ancorar! (fn [e d] (swap! ancoras conj [e d]))})]))
        ph/create-server ::ph/service-fn)))

(defn- como [ente iid & [extra]]
  (merge {"authorization" (str "Bearer " (json/write-value-as-string {:sub "u" :ente-id (str ente) :identidade-id (str iid)}))
          "content-type" "application/json"}
         extra))

(defn- ler [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(defn- trilha [svc ente iid & [q]]
  (ler (pt/response-for svc :get (str "/auditoria" (when q (str "?" q))) :headers (como ente iid))))

(deftest a-escrita-e-a-negacao-entram-seladas-e-a-leitura-comum-nao
  (let [ente (random-uuid) svc (servico ente (atom []))
        r (pt/response-for svc :post (str "/materias/" prop "/despachar") :body "{}"
                           :headers (como ente maria {"x-forwarded-for" "189.45.12.7, 10.0.0.1"}))]
    (is (= 201 (:status r)))
    (is (= {:ok true} (ler r)) "o corpo nao muda (o resumo do handler nao vai para o fio)")
    (is (= 403 (:status (pt/response-for svc :post "/so-secretaria" :body "{}" :headers (como ente rui)))))
    (is (= 200 (:status (pt/response-for svc :get "/ler" :headers (como ente maria)))))
    (is (= 401 (:status (pt/response-for svc :post (str "/materias/" prop "/despachar") :body "{}"))))
    (let [t (trilha svc ente ana)
          [negado despacho] (:registros t)]
      (is (= "casa" (:escopo t)))
      (is (= 2 (:total t)) "o despacho e a negacao; a leitura comum e o anonimo nao entram")
      (is (= ["legislativo/despachar" "escrita" "permitido" "Maria Secretária" "PL 7/2026" ["relator"] "189.45.x.x"]
             ((juxt :acao :classe :decisao (comp :nome :ator) (comp :rotulo :recurso) :campos :ip) despacho)))
      (is (= ["legislativo/so-secretaria" "negacao" "negado" "Rui Vereador"]
             ((juxt :acao :classe :decisao (comp :nome :ator)) negado)))
      (is (= (:selo despacho) (:selo-anterior negado)) "encadeado: cada selo sela o anterior")
      (is (= [{:acao "casa-provisionada" :operador "Rafaela" :selo "ab"}] (mapv #(dissoc % :em) (:operacao t)))
          "a atuacao da Operacao nesta Casa aparece para o auditor"))
    (testing "o IP completo fica no banco; so' ele pode ser anulado, e o resto nunca muda"
      (tenancy/com-tenant* *ds* ente
        (fn [tx]
          (is (= "189.45.12.7" (str (:ip (jdbc/execute-one! tx ["SELECT host(ip) AS ip FROM auditoria.registro WHERE seq = 1"]
                                                            {:builder-fn rs/as-unqualified-maps})))))
          (is (= 1 (:next.jdbc/update-count (jdbc/execute-one! tx ["UPDATE auditoria.registro SET ip = NULL WHERE seq = 1"]))))))
      (is (thrown? Exception (tenancy/com-tenant* *ds* ente #(jdbc/execute-one! % ["UPDATE auditoria.registro SET ip = '1.1.1.1' WHERE seq = 1"]))))
      (is (thrown? Exception (tenancy/com-tenant* *ds* ente #(jdbc/execute-one! % ["DELETE FROM auditoria.registro WHERE seq = 1"]))))
      (is (true? (:integra (ler (pt/response-for svc :get "/auditoria/integridade" :headers (como ente ana)))))
          "anular o IP nao quebra a corrente"))))

(deftest cada-papel-ve-o-seu-escopo
  (let [ente (random-uuid) svc (servico ente (atom []))]
    (pt/response-for svc :post (str "/materias/" prop "/despachar") :body "{}" :headers (como ente maria))
    (pt/response-for svc :post (str "/materias/" prop "/despachar") :body "{}" :headers (como ente rui))
    (pt/response-for svc :post "/identidade/acessos" :body "{}" :headers (como ente beto))
    (pt/response-for svc :post (str "/materias/" prop "/despachar") :body "{}" :headers (como ente cida))
    (testing "o vereador ve so' a propria trilha, e ler a propria nao e' leitura sensivel"
      (let [t (trilha svc ente rui)]
        (is (= ["propria" 1] [(:escopo t) (:total t)]))
        (is (nil? (:operacao t)))))
    (testing "o admin_ente ve os atos de acesso (o que ele administra) e os proprios"
      (is (= ["acessos" ["identidade/conceder-acesso"]]
             ((juxt :escopo #(mapv :acao (:registros %))) (trilha svc ente beto)))))
    (testing "o cidadao sai pseudonimizado; o auditor ve tudo — inclusive quem leu a trilha"
      (let [t (trilha svc ente ana)
            acoes (mapv :acao (:registros t))]
        (is (some #(re-matches #"Cidadão #[0-9a-f]{6}" (str (get-in % [:ator :nome]))) (:registros t)))
        (is (= 1 (count (filter #(= ["auditoria/trilha" "leitura_sensivel"] ((juxt :acao :classe) %)) (:registros t))))
            "a leitura do admin_ente ficou registrada (a do vereador sobre a propria, nao)")
        (is (some #{"identidade/conceder-acesso"} acoes))))
    (testing "filtros"
      (is (every? #(= "cidadao" (get-in % [:ator :tipo])) (:registros (trilha svc ente ana "ator=cidadao"))))
      (is (= 400 (:status (pt/response-for svc :get "/auditoria?classe=tudo" :headers (como ente ana))))))
    (testing "integridade e exportacao so' para o auditor"
      (is (= 403 (:status (pt/response-for svc :get "/auditoria/integridade" :headers (como ente beto)))))
      (let [r (pt/response-for svc :get "/auditoria/exportar.csv" :headers (como ente ana))]
        (is (= 200 (:status r)))
        (is (str/starts-with? (get-in r [:headers "Content-Type"]) "text/csv"))
        (is (str/includes? (:body r) "legislativo/despachar")))
      (is (= "auditoria/exportar" (:acao (first (:registros (trilha svc ente ana)))))
          "exportar a trilha fica registrado na trilha"))
    (testing "outra Casa nao ve nada desta (RLS)"
      (is (zero? (:total (trilha svc (random-uuid) ana)))))))

(defn- registro-de-ontem! [ente]
  (let [r {:ente-id ente :seq 1 :id (random-uuid) :ocorrido-em (.truncatedTo (.minus (Instant/now) 2 ChronoUnit/DAYS) ChronoUnit/MICROS)
           :ator-tipo "pessoa" :identidade-id maria :papeis ["secretario"] :acao "legislativo/despachar"
           :classe "escrita" :decisao "permitido" :status-http 201 :canal "web" :campos [] :detalhe {:metodo "POST"}}
        selo (logic/selo-de "" r)]
    (tenancy/com-tenant* *ds* ente
      #(jdbc/execute-one! % ["INSERT INTO auditoria.registro (ente_id, seq, id, ocorrido_em, ator_tipo, identidade_id, papeis,
                              acao, classe, decisao, status_http, canal, detalhe, selo_anterior, selo)
                              VALUES (?,?,?,?,?,?,'{secretario}',?,?,?,?,?,?,'',?)"
                             ente 1 (:id r) (Timestamp/from ^Instant (:ocorrido-em r)) "pessoa" maria "legislativo/despachar" "escrita" "permitido"
                             201 "web" (comum/->jsonb {:metodo "POST"}) selo]))
    selo))

(deftest o-dia-que-fecha-vira-selo-publicado-e-ancorado
  (let [ente (random-uuid) ancoras (atom []) svc (servico ente ancoras)
        selo-de-ontem (registro-de-ontem! ente)]
    (pt/response-for svc :post (str "/materias/" prop "/despachar") :body "{}" :headers (como ente maria))
    (is (= [[ente selo-de-ontem 1]] (mapv (fn [[e d]] [e (:selo d) (:seq d)]) @ancoras))
        "o primeiro registro de um dia novo fecha o anterior e o ancora na corrente da Operacao")
    (let [pub (ler (pt/response-for svc :get (str "/portal/casa/" ente "/integridade")))]
      (is (= [selo-de-ontem] (mapv :selo (:selos-do-dia pub))) "o portal publica o selo do dia (sem registro nenhum)"))
    (pt/response-for svc :post (str "/materias/" prop "/despachar") :body "{}" :headers (como ente maria))
    (is (= 1 (count @ancoras)) "no mesmo dia, nada novo a ancorar")
    (let [i (ler (pt/response-for svc :get "/auditoria/integridade" :headers (como ente ana)))]
      (is (= [true 3] [(:integra i) (:total i)]) "a corrente com o registro de ontem confere inteira"))
    (is (= 404 (:status (pt/response-for svc :get (str "/portal/casa/" (random-uuid) "/integridade")))))))
