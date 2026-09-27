(ns oplenario.conferencia-institucional-test
  "INTEGRACAO (PG real): B.8 / ADR-0013 — o AGENTE INSTITUCIONAL da Casa. O `admin_ente` liga a conferencia; o satelite
  pede a credencial de uma execucao (sem pessoa, so' `leitura`/`rascunho`), le a materia e as normas pelo catalogo e
  deixa um RASCUNHO de nota tecnica na fila da secretaria, que aproveita ou descarta. Desligar derruba o agente na
  chamada seguinte; sem concessao nao ha' credencial; nada vaza de uma Casa para outra."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.catalogo :as catalogo]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.autenticacao :as auten]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.identidade.db.identidade :as id]
            [oplenario.identidade.db.vinculo :as vinc]
            [oplenario.identidade.diplomat.http.in :as identidade-http]
            [oplenario.integracao-ia.components.repositorio :as repo-ia]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.legislativo.controllers :as controllers]
            [oplenario.legislativo.db.proposicao :as proposicao]
            [oplenario.legislativo.diplomat.http.in :as legislativo-http]
            [oplenario.migracao :as migracao])
  (:import (java.time Instant)))

(def ^:dynamic *c* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*c* c] (try (t) (finally (component/stop c)))))))

(def agente "conferencia-normativa")

(defn- repo-identidade [] (assoc (repo-id/repositorio) :datasource *c*))
(defn- repo-integracao [] (repo-ia/map->RepoIntegracaoIAPg {:datasource *c*}))
(defn- repo-legislativo [] (repo-leg/->RepoLegislativoPg *c* (outbox/bus)))

(defn- deps []
  {:repo-legislativo (repo-legislativo) :registrar-chamada (catalogo/registrador (repo-integracao))})

(defn- dv [ds] (let [r (mod (reduce + (map * ds (range (inc (count ds)) 1 -1))) 11)] (if (< r 2) 0 (- 11 r))))
(defn- cpf-valido [] (let [b (vec (repeatedly 9 #(rand-int 10))) d1 (dv b)] (apply str (concat b [d1 (dv (conj b d1))]))))

(defn- pessoa!
  "Uma pessoa com vinculo ativo e os `papeis` na Casa. Devolve o id."
  [ente & papeis]
  (let [iid (random-uuid)]
    (id/inserir! (:ds *c*) {:id iid :cpf (cpf-valido) :nome "Pessoa da Casa"})
    (tenancy/com-tenant* (:ds *c*) ente
      (fn [tx]
        (vinc/criar! tx {:id (random-uuid) :ente-id ente :identidade-id iid
                         :tipo (if (some #{"admin_ente"} papeis) "admin_ente" "servidor")})
        (doseq [p papeis]
          (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id ente :identidade-id iid :papel p}))))
    iid))

(defn- proposicao! [ente ementa]
  (tenancy/com-tenant* (:ds *c*) ente
    (fn [tx] (proposicao/protocolar! tx {:id (random-uuid) :ente-id ente :tipo "requerimento" :tipo-requerimento "informacao" :ano 2026
                                         :uf "CE" :municipio-nome "Baturite" :ementa ementa
                                         :autor-texto "Ver. Ana"}))))

(defn- ligar! [ente admin]
  (repo-id/conceder-agente! (repo-identidade) ente {:agente agente :classes ["leitura" "rascunho"]
                                                    :concedida-por admin}))

(defn- ator-da [{:keys [credencial]}] (auten/resolver-agente (repo-identidade) credencial))

(defn- como-json
  "O que o MCP entrega ao catalogo: JSON decodificado, chaves STRING em todos os niveis."
  [m]
  (json/read-value (json/write-value-as-string m)))

(defn- erro [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:tipo (ex-data e)))))

(def ^:private nota
  {:texto (str "O REQ conferido pede informacoes a Secretaria de Obras. [[materia:x | pede informacoes a Secretaria]]\n\n"
               "Aplica-se a Lei Organica, art. 25. [[norma:n#art25 | compete a Camara pedir informacoes]]")
   :citacoes [{:fonte-id "materia:x" :trecho "pede informacoes a Secretaria" :status "conferida" :rotulo "REQ 1/2026"}
              {:fonte-id "norma:n#art25" :trecho "compete a Camara pedir informacoes" :status "conferida"
               :rotulo "Lei Organica, art. 25"}]
   :paragrafos-sem-fonte []
   :incerteza "normal"
   :modelo "fake:determinístico"})

;; ---------- a concessao e a credencial ----------

(deftest sem-concessao-nao-ha-credencial
  (let [ente (random-uuid)]
    (is (nil? (auten/emitir-credencial-institucional! (repo-identidade) ente agente))
        "o admin_ente ainda nao ligou: o satelite nao roda nada")
    (ligar! ente (pessoa! ente "admin_ente"))
    (is (nil? (auten/emitir-credencial-institucional! (repo-identidade) ente "agente-inventado"))
        "agente fora da lista de agentes institucionais nao existe")
    (is (some? (auten/emitir-credencial-institucional! (repo-identidade) ente agente)))))

(deftest o-agente-institucional-le-e-deixa-rascunho
  (let [ente (random-uuid)
        admin (pessoa! ente "admin_ente")
        _ (ligar! ente admin)
        p (proposicao! ente "Requer informacoes sobre a reforma da praca.")
        c (auten/emitir-credencial-institucional! (repo-identidade) ente agente)
        ator (ator-da c)]
    (testing "o ator: sem pessoa, o papel de agente institucional, so' leitura e rascunho"
      (is (nil? (:identidade-id ator)))
      (is (= #{"agente_institucional"} (:papeis ator)))
      (is (= {:agente agente :execucao-id (:execucao-id c) :publico :institucional :classes #{:leitura :rascunho}
              :institucional? true}
             (:via ator))))
    (testing "o que ele ve: ler a materia e as normas, e registrar a nota — nada mais"
      (is (= ["buscar_dispositivos" "ler_dispositivo" "registrar_nota_tecnica" "situacao_da_materia"]
             (mapv :nome (catalogo/ferramentas ator)))))
    (testing "le a materia protocolada"
      (is (= "Requer informacoes sobre a reforma da praca."
             (:ementa (catalogo/executar! (deps) ator "situacao_da_materia" {:proposicao-id (str (:id p))})))))
    (testing "registra o rascunho na fila; tentar de novo devolve a mesma nota"
      (let [r1 (catalogo/executar! (deps) ator "registrar_nota_tecnica"
                                   (como-json (assoc nota :proposicao-id (str (:id p)))))
            r2 (catalogo/executar! (deps) ator "registrar_nota_tecnica"
                                   (assoc nota :proposicao-id (str (:id p)) :texto "outra coisa"))]
        (is (= "pendente" (:estado r1)))
        (is (re-find #"Ninguem decidiu nada" (:mensagem r1)))
        (is (= (:nota-id r1) (:nota-id r2)))
        (let [n (controllers/nota-tecnica (repo-legislativo) ente (parse-uuid (:nota-id r1)))]
          (is (= agente (:agente n)))
          (is (= (:execucao-id c) (:execucao-id n)) "a execucao vem da credencial, nunca da entrada")
          (is (re-find #"Secretaria de Obras" (:texto n)) "a segunda chamada nao muda a nota")
          (is (= ["conferida" "conferida"] (mapv :status (:citacoes n)))))
        (is (= [["registrar_nota_tecnica" "rascunho" "ok"] ["registrar_nota_tecnica" "rascunho" "ok"]]
               (mapv (juxt :ferramenta :classe :desfecho)
                     (repo-ia/chamadas-da-execucao (repo-integracao) ente (:execucao-id c))))
            "escrita do agente institucional vai ao audit, sem pessoa")))
    (testing "proposicao de outra Casa (ou inexistente): nao encontrada, nada gravado"
      (is (nil? (catalogo/executar! (deps) ator "registrar_nota_tecnica"
                                    (assoc nota :proposicao-id (str (:id (proposicao! (random-uuid) "outra"))))))))
    (testing "nunca `ato`: a credencial institucional com ato nem entra no banco"
      (is (thrown? Exception
                   (repo-id/emitir-credencial-agente! (repo-identidade)
                                                      {:execucao-id (random-uuid) :ente-id ente :identidade-id nil
                                                       :agente agente :publico "institucional"
                                                       :classes ["leitura" "ato"]
                                                       :expira-em (.plusSeconds (Instant/now) 60)}))))
    (testing "desligar derruba o agente na chamada seguinte, com a credencial ainda valida"
      (repo-id/revogar-agente! (repo-identidade) ente agente admin)
      (let [depois (ator-da c)]
        (is (= #{} (:papeis depois)))
        (is (= #{} (get-in depois [:via :classes])))
        (is (empty? (catalogo/ferramentas depois)))
        (is (= :autorizacao/negado
               (erro #(catalogo/executar! (deps) depois "situacao_da_materia" {:proposicao-id (str (:id p))})))))
      (is (nil? (auten/emitir-credencial-institucional! (repo-identidade) ente agente))
          "e nenhuma execucao nova comeca"))))

(deftest credencial-institucional-so-sem-pessoa-e-pessoa-nunca-institucional
  (let [ente (random-uuid)
        base {:execucao-id (random-uuid) :ente-id ente :agente agente :classes ["leitura"]
              :expira-em (.plusSeconds (Instant/now) 60)}]
    (is (thrown? Exception (repo-id/emitir-credencial-agente! (repo-identidade)
                                                              (assoc base :identidade-id nil :publico "secretaria")))
        "sem pessoa, so' o publico institucional")
    (is (thrown? Exception (repo-id/emitir-credencial-agente! (repo-identidade)
                                                              (assoc base :identidade-id (random-uuid)
                                                                          :publico "institucional")))
        "com pessoa, nunca o publico institucional")))

(deftest a-concessao-e-da-casa
  (let [a (random-uuid) b (random-uuid)]
    (ligar! a (pessoa! a "admin_ente"))
    (is (some? (repo-id/concessao-agente (repo-identidade) a agente)))
    (is (nil? (repo-id/concessao-agente (repo-identidade) b agente)) "ligar na Casa A nao liga na B")
    (is (nil? (auten/emitir-credencial-institucional! (repo-identidade) b agente)))
    (testing "ligar de novo nao abre outra concessao"
      (let [c1 (repo-id/concessao-agente (repo-identidade) a agente)]
        (ligar! a (random-uuid))
        (is (= (:id c1) (:id (repo-id/concessao-agente (repo-identidade) a agente))))))))

;; ---------- a decisao da secretaria ----------

(defn- nota! [ente]
  (let [p (proposicao! ente "Requer informacoes sobre a merenda.")]
    (controllers/registrar-nota-tecnica! (repo-legislativo)
                                         {:ente-id ente :via {:agente agente :execucao-id (random-uuid)}}
                                         (assoc nota :proposicao-id (:id p)))))

(deftest a-secretaria-aproveita-ou-descarta
  (let [ente (random-uuid)
        secretaria {:ente-id ente :identidade-id (random-uuid) :papeis #{"secretario"}}
        n1 (nota! ente)
        n2 (nota! ente)]
    (is (= [(:id n1) (:id n2)] (mapv :id (controllers/notas-tecnicas (repo-legislativo) ente "pendente")))
        "a fila, na ordem em que chegou")
    (testing "aproveitar sem editar guarda o texto do agente sem as marcas de citacao"
      (let [d (controllers/decidir-nota-tecnica! (repo-legislativo) secretaria (:id n1) {:desfecho "aproveitada"})]
        (is (= "aproveitada" (:estado d)))
        (is (= (str "O REQ conferido pede informacoes a Secretaria de Obras.\n\n"
                    "Aplica-se a Lei Organica, art. 25.")
               (:texto-final d)))
        (is (= (:identidade-id secretaria) (:decidida-por d)))))
    (testing "decidida uma vez so'"
      (is (= :conflito/nota-decidida
             (erro #(controllers/decidir-nota-tecnica! (repo-legislativo) secretaria (:id n1)
                                                       {:desfecho "descartada"})))))
    (testing "descartar nao guarda texto"
      (let [d (controllers/decidir-nota-tecnica! (repo-legislativo) secretaria (:id n2) {:desfecho "descartada"})]
        (is (= "descartada" (:estado d)))
        (is (nil? (:texto-final d)))))
    (is (empty? (controllers/notas-tecnicas (repo-legislativo) ente "pendente")))
    (is (= 2 (count (controllers/notas-tecnicas (repo-legislativo) ente nil))))
    (testing "a nota de uma Casa nao existe para outra"
      (is (nil? (controllers/nota-tecnica (repo-legislativo) (random-uuid) (:id n1))))
      (is (nil? (controllers/decidir-nota-tecnica! (repo-legislativo) (assoc secretaria :ente-id (random-uuid))
                                                   (:id n2) {:desfecho "descartada"}))))))

;; ---------- as telas: o admin liga e desliga; a secretaria ve e decide ----------

(defn- servico []
  (let [auth (it/autenticacao (idp-dev/idp-dev) (repo-identidade))]
    (-> (http/servico (config/carregar)
                      (into (identidade-http/rotas {:auth auth :repo-identidade (repo-identidade)})
                            (legislativo-http/rotas {:auth auth :repo-legislativo (repo-legislativo)}))
                      it/globais)
        ph/create-server ::ph/service-fn)))

(defn- pedir [svc metodo caminho ente pessoa & [corpo]]
  (let [r (pt/response-for svc metodo caminho
                           :headers {"Authorization" (str "Bearer " (json/write-value-as-string
                                                                     {:sub "u" :ente-id (str ente)
                                                                      :identidade-id (str pessoa)}))
                                     "Content-Type" "application/json"}
                           :body (when corpo (json/write-value-as-string corpo)))]
    {:status (:status r) :corpo (when (seq (:body r)) (json/read-value (:body r) json/keyword-keys-object-mapper))}))

(deftest as-telas
  (let [svc (servico)
        ente (random-uuid)
        admin (pessoa! ente "admin_ente")
        secretaria (pessoa! ente "secretario")
        caminho (str "/identidade/agentes-institucionais/" agente "/concessao")]
    (testing "a secretaria ve o agente desligado, e nao liga"
      (let [{:keys [status corpo]} (pedir svc :get "/identidade/agentes-institucionais" ente secretaria)]
        (is (= 200 status))
        (is (= [[agente false]] (mapv (juxt :agente :ligado) (:itens corpo))))
        (is (= ["leitura" "rascunho"] (:classes (first (:itens corpo))))))
      (is (= 403 (:status (pedir svc :put caminho ente secretaria)))))
    (testing "o admin liga e desliga"
      (let [{:keys [status corpo]} (pedir svc :put caminho ente admin)]
        (is (= 200 status))
        (is (true? (:ligado (first (:itens corpo)))))
        (is (string? (:ligado-em (first (:itens corpo))))))
      (is (some? (auten/emitir-credencial-institucional! (repo-identidade) ente agente)))
      (is (false? (:ligado (first (:itens (:corpo (pedir svc :delete caminho ente admin))))))))
    (is (= 404 (:status (pedir svc :put "/identidade/agentes-institucionais/inventado/concessao" ente admin))))
    (testing "a fila da secretaria"
      (let [n (nota! ente)
            fila (pedir svc :get "/legislativo/notas-tecnicas" ente secretaria)]
        (is (= 200 (:status fila)))
        (is (= [(str (:id n))] (mapv :id (:itens (:corpo fila)))))
        (is (= "requerimento" (:tipo (first (:itens (:corpo fila))))))
        (let [{:keys [status corpo]} (pedir svc :get (str "/legislativo/notas-tecnicas/" (:id n)) ente secretaria)]
          (is (= 200 status))
          (is (= "Lei Organica, art. 25" (:rotulo (second (:citacoes corpo)))))
          (is (not (re-find #"\[\[" (:texto-limpo corpo)))))
        (is (= 400 (:status (pedir svc :post (str "/legislativo/notas-tecnicas/" (:id n) "/decisao") ente secretaria
                                   {:desfecho "aprovada"}))))
        (is (= 403 (:status (pedir svc :get "/legislativo/notas-tecnicas" ente admin))) "a fila e' da secretaria")
        (let [{:keys [status corpo]} (pedir svc :post (str "/legislativo/notas-tecnicas/" (:id n) "/decisao") ente
                                            secretaria {:desfecho "aproveitada" :texto "Texto revisado pela secretaria."})]
          (is (= 200 status))
          (is (= "Texto revisado pela secretaria." (:texto-final corpo))))
        (is (= 409 (:status (pedir svc :post (str "/legislativo/notas-tecnicas/" (:id n) "/decisao") ente secretaria
                                   {:desfecho "descartada"}))))
        (is (= [] (:itens (:corpo (pedir svc :get "/legislativo/notas-tecnicas" ente secretaria)))))
        (is (= 1 (count (:itens (:corpo (pedir svc :get "/legislativo/notas-tecnicas?estado=todas" ente secretaria))))))))))
