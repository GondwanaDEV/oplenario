(ns oplenario.legislativo.parecer-nota-http-in-test
  "INTEGRACAO (PG real + a cadeia HTTP inteira de `rotas/montar`): a fatia 2a da ADR-0019. A nota tecnica da IA chega a fila
  do JURIDICO (Eixo 5) e vira rascunho do parecer por `POST /legislativo/notas-tecnicas/:id/rascunho-juridico`; o
  `admin_ente` antecipa (ou nao) o parecer ao portal (Eixo 4). Identidade e cadastros FAKE (papeis, perfil, e o seam
  `casa-tem-papel-ativo?`); legislativo REAL. Prova os gates de papel, os 404/409, a Casa SEM juridico (a nota segue para a
  secretaria como sempre), que o texto da IA nunca e' chamado de parecer e o portal antecipado x nao antecipado."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.cadastros.components.repositorio :as repo-cadastros-comp]
            [oplenario.cadastros.relacoes.cadastro :as rel-cad]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.identidade.relacoes.identidade :as rel-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.legislativo.diplomat.http.in :as legislativo-http]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.registro-fatos :as rf]
            [oplenario.rotas :as rotas])
  (:import (java.time Instant)))

(def ^:dynamic *repo* nil)
(def ^:dynamic *svc* nil)

(def ente (random-uuid))
(def sec (random-uuid))
(def jur (random-uuid))
(def ver (random-uuid))
(def admin (random-uuid))

(def papeis {sec #{"secretario"} jur #{"juridico"} ver #{"vereador"} admin #{"admin_ente"}})

;; a Casa tem juridico ativo? — o fake da identidade responde por este atom (o teste da Casa sem juridico o desliga)
(def juridico-ativo (atom true))

(defn- fake-identidade []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ id] {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis (get papeis id #{})})
    (vinculos-de [_ _ id] (when (contains? papeis id) [{:tipo "servidor"}]))
    (nome-por-id [_ id] {:nome (condp = id sec "Marina Freire" jur "Paulo Bezerra" "Fulano")})
    (casa-tem-papel-ativo? [_ _ papel] (and (= "juridico" papel) @juridico-ativo))
    (perfil-juridico [_ _ id]
      (when (= id jur) {:nome "Paulo Bezerra" :qualificacao "efetivo" :oab "CE 12345"}))))

(defn- fake-cadastros []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cadastros-comp/RepoCadastros
    (vereador-por-identidade [_ _ _] nil)))

(use-fixtures :once
  (fn [t]
    (let [c   (component/start (datasource/datasource (config/carregar)))
          reg (component/start (rf/registro-fatos (merge rel-cad/relacoes rel-id/relacoes)))]
      (migracao/migrar! (:ds c))
      (let [r (repo/->RepoLegislativoPg c (outbox/bus))]
        (binding [*repo* r
                  *svc* (-> (http/servico (config/carregar)
                                          (rotas/montar {:idp (idp-dev/idp-dev) :repo-identidade (fake-identidade)
                                                         :repo-legislativo r :repo-cadastros (fake-cadastros)
                                                         :registro reg
                                                         :relogio (tempo/relogio-fixo (Instant/parse "2026-09-30T12:00:00Z"))})
                                          it/globais)
                            ph/create-server ::ph/service-fn)]
          (try (t) (finally (component/stop reg) (component/stop c))))))))

(defn- cab [quem] {"authorization" (str "Bearer " (json/write-value-as-string
                                                     {:sub "u" :ente-id (str ente) :identidade-id (str quem)}))
                   "Content-Type" "application/json"})

(defn- chamar
  ([quem metodo url] (chamar quem metodo url nil))
  ([quem metodo url corpo]
   (let [r (apply pt/response-for *svc* metodo url :headers (cab quem)
                  (when corpo [:body (json/write-value-as-string corpo)]))]
     {:status (:status r)
      :corpo (when (seq (:body r)) (json/read-value (:body r) json/keyword-keys-object-mapper))})))

(defn- protocolar! []
  (:id (repo/protocolar! *repo* ente {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                                      :municipio-nome "Fortaleza" :ementa "Institui o Programa de Hortas"})))

(defn- nota!
  "Uma nota por (materia, agente): para uma segunda nota da mesma materia, passe outro `agente`."
  ([pid] (nota! pid "conferencia-normativa"))
  ([pid agente]
   (:id (repo/registrar-nota-tecnica! *repo* ente
          {:proposicao-id pid :agente agente :execucao-id (random-uuid)
          :texto (str "O projeto institui hortas. [[materia:x | institui hortas]]\n\n"
                      "Aplica-se a LOM, art. 25. [[norma:n#art25 | compete a Camara]]")
          :citacoes [{:fonte-id "materia:x" :trecho "institui hortas" :status "conferida" :rotulo "PL 1/2026"}]
          :paragrafos-sem-fonte [] :incerteza "normal" :motivos-incerteza [] :modelo-llm-id "fake"}))))

(defn- fila-ids [quem]
  (mapv :id (:itens (:corpo (chamar quem :get "/legislativo/notas-tecnicas?estado=pendente")))))

(deftest a-nota-chega-ao-juridico-e-continua-com-a-secretaria
  (reset! juridico-ativo true)
  (let [nid (str (nota! (protocolar!)))]
    (testing "o juridico le a fila e a nota inteira (texto e citacoes), como a secretaria"
      (let [{:keys [status corpo]} (chamar jur :get "/legislativo/notas-tecnicas?estado=pendente")]
        (is (= 200 status))
        (is (true? (:casa-com-juridico corpo)) "a fila diz que a Casa tem juridico ativo")
        (is (some #{nid} (map :id (:itens corpo)))))
      (let [{:keys [status corpo]} (chamar jur :get (str "/legislativo/notas-tecnicas/" nid))]
        (is (= 200 status))
        (is (str/includes? (:texto corpo) "[["))
        (is (not (str/includes? (:texto-limpo corpo) "[[")))
        (is (= 1 (count (:citacoes corpo))))))
    (testing "a secretaria segue vendo a nota pendente (e sabe que a Casa tem juridico)"
      (is (some #{nid} (fila-ids sec)))
      (is (true? (:casa-com-juridico (:corpo (chamar sec :get "/legislativo/notas-tecnicas"))))))
    (testing "vereador e admin_ente nao leem a fila de notas"
      (is (= 403 (:status (chamar ver :get "/legislativo/notas-tecnicas"))))
      (is (= 403 (:status (chamar admin :get "/legislativo/notas-tecnicas"))))
      (is (= 403 (:status (chamar ver :get (str "/legislativo/notas-tecnicas/" nid))))))
    (testing "o juridico so' le: aproveitar/descartar segue sendo da secretaria"
      (is (= 403 (:status (chamar jur :post (str "/legislativo/notas-tecnicas/" nid "/decisao") {:desfecho "descartada"})))))))

(deftest usar-como-rascunho-numa-tx-e-tira-a-nota-da-fila-das-duas
  (reset! juridico-ativo true)
  (let [pid (protocolar!) nid (str (nota! pid))
        url (str "/legislativo/notas-tecnicas/" nid "/rascunho-juridico")]
    (testing "so' o juridico usa: secretaria, vereador e admin_ente nao"
      (doseq [quem [sec ver admin]]
        (is (= 403 (:status (chamar quem :post url {}))) (str quem))))
    (testing "nota inexistente -> 404"
      (is (= 404 (:status (chamar jur :post (str "/legislativo/notas-tecnicas/" (random-uuid) "/rascunho-juridico") {})))))
    (let [{:keys [status corpo]} (chamar jur :post url {})
          par (:parecer corpo)]
      (testing "201 com o pedido e o rascunho aberto"
        (is (= 201 status))
        (is (= ["nota_tecnica" "pendente" "Paulo Bezerra" (str pid)]
               [(:origem corpo) (:estado corpo) (:pedido-por corpo) (get-in corpo [:proposicao :id])])))
      (testing "o rascunho: texto sem marcas, relatorio neutro, SEM conclusao, origem informada"
        (is (= "rascunho" (:estado par)))
        (is (nil? (:conclusao par)))
        (is (nil? (:assinatura par)))
        (is (= "nota_tecnica" (:origem-rascunho par)))
        (is (str/includes? (:relatorio par) "nota técnica da IA — a revisar"))
        (is (str/includes? (:fundamentacao par) "O projeto institui hortas."))
        (is (not (str/includes? (:fundamentacao par) "[["))))
      (testing "o texto da IA nao e' chamado de parecer: nao ha' numero e o pedido segue pendente"
        (is (nil? (:numero par)))
        (is (= "pendente" (:estado corpo))))
      (testing "a nota saiu da fila das duas e ficou aproveitada"
        (is (not (some #{nid} (fila-ids jur))))
        (is (not (some #{nid} (fila-ids sec))))
        (let [n (:corpo (chamar sec :get (str "/legislativo/notas-tecnicas/" nid)))]
          (is (= "aproveitada" (:estado n)))
          (is (= (:fundamentacao par) (:texto-final n)))))
      (testing "quem usou depois leva 409; a secretaria tambem nao decide mais"
        (is (= 409 (:status (chamar jur :post url {}))))
        (is (= 409 (:status (chamar sec :post (str "/legislativo/notas-tecnicas/" nid "/decisao") {:desfecho "descartada"})))))
      (testing "o advogado abre o pedido, escreve a conclusao e assina — o parecer e' dele"
        (let [base (str "/legislativo/pedidos-parecer-juridico/" (:id corpo))]
          (is (= 400 (:status (chamar jur :post (str base "/parecer/assinatura") {}))) "sem conclusao nao assina")
          (is (= 200 (:status (chamar jur :put (str base "/parecer")
                                      {:relatorio (:relatorio par) :fundamentacao (:fundamentacao par) :conclusao "com_ressalvas"}))))
          (let [a (chamar jur :post (str base "/parecer/assinatura") {})]
            (is (= 200 (:status a)))
            (is (= ["assinado" "Paulo Bezerra" "nota_tecnica"]
                   [(get-in a [:corpo :parecer :estado]) (get-in a [:corpo :parecer :assinatura :nome])
                    (get-in a [:corpo :parecer :origem-rascunho])])))
          (testing "a ficha interna traz a origem; o portal NAO"
            (let [ficha (chamar sec :get (str "/legislativo/proposicoes/" pid "/pareceres-juridicos"))]
              (is (= ["nota_tecnica"] (mapv :origem-rascunho (:pareceres (:corpo ficha))))))
            (is (not (str/includes? (pr-str (legislativo-http/pareceres-juridicos-publicos-wire *repo* ente pid))
                                    "origem")))))))))

(deftest usar-a-nota-num-pedido-da-secretaria-e-o-409-do-rascunho-em-curso
  (reset! juridico-ativo true)
  (let [pid (protocolar!) nid (str (nota! pid))
        ped (:corpo (chamar sec :post "/legislativo/pedidos-parecer-juridico" {:proposicao-id (str pid) :em-nome-de "Presidência"}))]
    (testing "o pedido pendente da secretaria e' reaproveitado"
      (let [{:keys [status corpo]} (chamar jur :post (str "/legislativo/notas-tecnicas/" nid "/rascunho-juridico") {})]
        (is (= 201 status))
        (is (= [(:id ped) "secretaria" "Presidência"] ((juxt :id :origem :em-nome-de) corpo)))))
    (testing "materia cujo pedido ja' tem rascunho em curso: 409 e a nota nova fica na fila"
      (let [nid2 (str (nota! pid "outro-agente"))
            {:keys [status corpo]} (chamar jur :post (str "/legislativo/notas-tecnicas/" nid2 "/rascunho-juridico") {})]
        (is (= 409 status))
        (is (str/includes? (:erro corpo) "rascunho em curso"))
        (is (some #{nid2} (fila-ids jur)) "nada foi consumido")))))

(deftest casa-sem-juridico-a-nota-segue-para-a-secretaria-como-hoje
  (reset! juridico-ativo false)
  (try
    (let [nid (str (nota! (protocolar!)))]
      (testing "a fila da secretaria segue igual, e diz que a Casa nao tem juridico"
        (let [{:keys [status corpo]} (chamar sec :get "/legislativo/notas-tecnicas?estado=pendente")]
          (is (= 200 status))
          (is (false? (:casa-com-juridico corpo)))
          (is (some #{nid} (map :id (:itens corpo))))))
      (testing "a secretaria aproveita como sempre"
        (let [{:keys [status corpo]} (chamar sec :post (str "/legislativo/notas-tecnicas/" nid "/decisao") {:desfecho "aproveitada"})]
          (is (= 200 status))
          (is (= "aproveitada" (:estado corpo)))))
      (testing "defensivo: mesmo com o papel, sem juridico ativo na Casa a rota responde 409 nomeado (nao 500)"
        (let [outra (str (nota! (protocolar!)))
              {:keys [status corpo]} (chamar jur :post (str "/legislativo/notas-tecnicas/" outra "/rascunho-juridico") {})]
          (is (= 409 status))
          (is (= "Esta Casa não tem jurídico ativo." (:erro corpo)))
          (is (some #{outra} (fila-ids sec)) "a nota segue pendente para a secretaria"))))
    (finally (reset! juridico-ativo true))))

;; ---------------- antecipar o portal (admin_ente) ----------------

(def ^:private texto {:relatorio "Trata-se de projeto de lei." :fundamentacao "Art. 30, I, da CF." :conclusao "favoravel"})

(defn- assinar-da-materia! [pid]
  (let [ped (:corpo (chamar sec :post "/legislativo/pedidos-parecer-juridico" {:proposicao-id (str pid)}))
        base (str "/legislativo/pedidos-parecer-juridico/" (:id ped))]
    (chamar jur :put (str base "/parecer") texto)
    (chamar jur :post (str base "/parecer/assinatura") {})))

(defn- publicos [pid] (:pareceres (legislativo-http/pareceres-juridicos-publicos-wire *repo* ente pid)))

(deftest o-admin-antecipa-o-parecer-ao-portal
  (let [url "/legislativo/parametros-parecer-juridico"]
    (testing "so' o admin_ente le e altera (a secretaria e o juridico nao)"
      (doseq [quem [sec jur ver]]
        (is (= 403 (:status (chamar quem :get url))) (str quem))
        (is (= 403 (:status (chamar quem :put url {:publicar-ao-assinar true}))) (str quem))))
    (testing "padrao: so' depois da deliberacao"
      (is (= {:publicar-ao-assinar false} (:corpo (chamar admin :get url)))))
    (testing "corpo invalido -> 400 (um texto \"false\" nao pode virar verdadeiro)"
      (is (= 400 (:status (chamar admin :put url {}))))
      (is (= 400 (:status (chamar admin :put url {:publicar-ao-assinar "false"}))))
      (is (= 400 (:status (chamar admin :put url {:publicar-ao-assinar 1}))))
      (is (= {:publicar-ao-assinar false} (:corpo (chamar admin :get url)))))
    (let [pid (protocolar!)]
      (assinar-da-materia! pid)
      (testing "nao antecipado: assinado, a materia em curso, nada no portal"
        (is (empty? (publicos pid))))
      (testing "antecipado: aparece ao assinar"
        (is (= {:publicar-ao-assinar true} (:corpo (chamar admin :put url {:publicar-ao-assinar true}))))
        (is (= {:publicar-ao-assinar true} (:corpo (chamar admin :get url))))
        (is (= ["favoravel"] (mapv :conclusao (publicos pid))))
        (is (= "Paulo Bezerra" (:nome (:assinatura (first (publicos pid))))))
        (let [pid2 (protocolar!)]
          (assinar-da-materia! pid2)
          (is (= 1 (count (publicos pid2))) "a proxima materia ja' sai publicada ao assinar")))
      (testing "consulta avulsa nunca vai ao portal, mesmo antecipado"
        (let [av (:corpo (chamar sec :post "/legislativo/pedidos-parecer-juridico" {:assunto "Consulta avulsa da Presidencia"}))
              base (str "/legislativo/pedidos-parecer-juridico/" (:id av))]
          (chamar jur :put (str base "/parecer") texto)
          (is (= 200 (:status (chamar jur :post (str base "/parecer/assinatura") {}))))
          (is (empty? (publicos (random-uuid))))
          (is (= 1 (count (publicos pid))))))
      (testing "desligar volta a esperar a deliberacao"
        (is (= {:publicar-ao-assinar false} (:corpo (chamar admin :put url {:publicar-ao-assinar false}))))
        (is (empty? (publicos pid)))))))
