(ns oplenario.legislativo.parecer-juridico-http-in-test
  "INTEGRACAO (PG real + a cadeia HTTP inteira de `rotas/montar`): o caminho da comissao e o parecer juridico (ADR-0019
  fatia 1). Identidade e cadastros FAKE (papeis, perfil juridico, comissoes, nomes); legislativo REAL. Prova o gate de
  papel de cada rota, o contrato de cada status (400/403/404/409), o rascunho oculto a secretaria, a assinatura com o
  snapshot do PERFIL (nunca do corpo), a substituicao e o encaminhamento as comissoes com o relator na ficha."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
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
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.registro-fatos :as rf]
            [oplenario.rotas :as rotas])
  (:import (java.time Instant)))

(def ^:dynamic *repo* nil)
(def ^:dynamic *svc* nil)

(def ente (random-uuid))
(def sec (random-uuid))
(def jur (random-uuid))
(def jur-sem-perfil (random-uuid))
(def ver (random-uuid))
(def ver-id (random-uuid))          ; o CADASTRO de vereador da identidade `ver` (o relator)
(def outro-ver-id (random-uuid))
(def ccj (random-uuid))
(def fin (random-uuid))

(def papeis {sec #{"secretario"} jur #{"juridico"} jur-sem-perfil #{"juridico"} ver #{"vereador"}})

(defn- fake-identidade []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ id] {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis (get papeis id #{})})
    (vinculos-de [_ _ id] (when (contains? papeis id) [{:tipo "servidor"}]))
    (nome-por-id [_ id] {:nome (condp = id sec "Marina Freire" jur "Paulo Bezerra" ver "Vereadora Ana" "Fulano")})
    (perfil-juridico [_ _ id]
      (when (= id jur) {:nome "Paulo Bezerra" :qualificacao "efetivo" :oab "CE 12345"}))))

(def nomes-vereador {ver-id "Ana Vereadora" outro-ver-id "Beto Vereador"})

(defn- fake-cadastros []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cadastros-comp/RepoCadastros
    (vereador-por-identidade [_ _ id] (when (= id ver) {:id ver-id}))
    (buscar-vereador [_ _ id] (when (contains? nomes-vereador id) {:id id}))
    (nomes-de-vereadores [_ _ ids] (into {} (keep (fn [i] (when-let [n (nomes-vereador i)] [i n]))) ids))
    (comissoes-vigentes [_ _ _] [{:id ccj :nome "Comissão de Constituição e Justiça"} {:id fin :nome "Comissão de Finanças"}])
    (nomes-de-comissoes [_ _ ids]
      (into {} (keep (fn [i] (condp = i ccj [i "Comissão de Constituição e Justiça"] fin [i "Comissão de Finanças"] nil))) ids))))

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

(def ^:private texto {:relatorio "Trata-se de projeto de lei." :fundamentacao "Art. 30, I, da CF." :conclusao "favoravel"})

(deftest pedir-parecer-sobre-materia-e-consulta-avulsa
  (let [pid (protocolar!)]
    (testing "a secretaria pede sobre a materia (assunto padrao) e o pedido traz a referencia"
      (let [{:keys [status corpo]} (chamar sec :post "/legislativo/pedidos-parecer-juridico"
                                           {:proposicao-id (str pid) :prazo "2026-12-01" :em-nome-de "Presidência"})]
        (is (= 201 status))
        (is (= ["pendente" "secretaria" "Presidência" "2026-12-01" "Marina Freire" "Análise jurídica da matéria"]
               ((juxt :estado :origem :em-nome-de :prazo :pedido-por :assunto) corpo)))
        (is (= (str pid) (get-in corpo [:proposicao :id])))
        (is (re-matches #"PL \d{3}/2026" (get-in corpo [:proposicao :ref])))))
    (testing "consulta avulsa exige assunto"
      (is (= 400 (:status (chamar sec :post "/legislativo/pedidos-parecer-juridico" {}))))
      (is (= 201 (:status (chamar sec :post "/legislativo/pedidos-parecer-juridico"
                                  {:assunto "Prazo regimental da leitura de expediente"})))))
    (testing "materia que nao existe -> 404; corpo invalido -> 400"
      (is (= 404 (:status (chamar sec :post "/legislativo/pedidos-parecer-juridico" {:proposicao-id (str (random-uuid))}))))
      (is (= 400 (:status (chamar sec :post "/legislativo/pedidos-parecer-juridico" {:proposicao-id "x"}))))
      (is (= 400 (:status (chamar sec :post "/legislativo/pedidos-parecer-juridico" {:assunto "Consulta valida" :prazo "amanha"})))))
    (testing "so' a secretaria pede: o advogado e o vereador nao"
      (is (= 403 (:status (chamar jur :post "/legislativo/pedidos-parecer-juridico" {:assunto "Consulta valida"}))))
      (is (= 403 (:status (chamar ver :post "/legislativo/pedidos-parecer-juridico" {:assunto "Consulta valida"})))))))

(deftest a-fila-e-do-juridico-e-da-secretaria
  (chamar sec :post "/legislativo/pedidos-parecer-juridico" {:assunto "Consulta para a fila"})
  (let [{:keys [status corpo]} (chamar jur :get "/legislativo/pedidos-parecer-juridico")]
    (is (= 200 status))
    (is (seq (:pedidos corpo))))
  (is (= 200 (:status (chamar sec :get "/legislativo/pedidos-parecer-juridico?estado=todos"))))
  (is (= 400 (:status (chamar sec :get "/legislativo/pedidos-parecer-juridico?estado=xyz"))))
  (is (= 403 (:status (chamar ver :get "/legislativo/pedidos-parecer-juridico"))) "vereador nao le a fila"))

(deftest do-rascunho-a-assinatura-e-a-substituicao
  (let [pid (protocolar!)
        pedido (:corpo (chamar sec :post "/legislativo/pedidos-parecer-juridico" {:proposicao-id (str pid)}))
        base (str "/legislativo/pedidos-parecer-juridico/" (:id pedido))]
    (testing "so' o juridico escreve"
      (is (= 403 (:status (chamar sec :put (str base "/parecer") texto))))
      (is (= 403 (:status (chamar ver :put (str base "/parecer") texto)))))
    (testing "o rascunho aceita texto incompleto; a secretaria ve' que esta' sendo redigido, sem o texto"
      (let [r (chamar jur :put (str base "/parecer") {:relatorio "so' o relatorio" :fundamentacao ""})]
        (is (= 200 (:status r)))
        (is (= ["rascunho" "so' o relatorio"] ((juxt #(get-in % [:parecer :estado]) #(get-in % [:parecer :relatorio])) (:corpo r)))))
      (let [{:keys [corpo]} (chamar sec :get base)]
        (is (= "rascunho" (get-in corpo [:parecer :estado])))
        (is (not (contains? (:parecer corpo) :relatorio)) "texto do rascunho e' so' do juridico"))
      (is (= "so' o relatorio" (get-in (chamar jur :get base) [:corpo :parecer :relatorio]))))
    (testing "assinar exige o texto completo (400) e um perfil juridico (403)"
      (is (= 400 (:status (chamar jur :post (str base "/parecer/assinatura") {}))))
      (chamar jur :put (str base "/parecer") texto)
      (is (= 403 (:status (chamar jur-sem-perfil :post (str base "/parecer/assinatura") {})))
          "sem qualificacao e OAB no acesso, nao assina"))
    (testing "assinar: numero/ano, snapshot do PERFIL (nao do corpo), pedido atendido"
      (let [{:keys [status corpo]} (chamar jur :post (str base "/parecer/assinatura") {:oab "SP 999" :nome "Impostor"})
            pj (:parecer corpo)]
        (is (= 200 status))
        (is (= "atendido" (:estado corpo)))
        (is (= ["assinado" 1 "favoravel"] ((juxt :estado :numero :conclusao) pj)))
        (is (= {:nome "Paulo Bezerra" :oab "CE 12345" :qualificacao "efetivo"} (select-keys (:assinatura pj) [:nome :oab :qualificacao])))))
    (testing "assinado: 409 para editar/reassinar; a secretaria le' o texto assinado"
      (is (= 409 (:status (chamar jur :put (str base "/parecer") texto))))
      (is (= 409 (:status (chamar jur :post (str base "/parecer/assinatura") {}))))
      (is (= "Trata-se de projeto de lei." (get-in (chamar sec :get base) [:corpo :parecer :relatorio]))))
    (testing "a ficha da materia lista o parecer assinado (secretaria, vereador e juridico leem)"
      (doseq [quem [sec ver jur]]
        (let [{:keys [status corpo]} (chamar quem :get (str "/legislativo/proposicoes/" pid "/pareceres-juridicos"))]
          (is (= 200 status))
          (is (= ["favoravel" false] ((juxt :conclusao :substituido) (first (:pareceres corpo))))))))
    (testing "substituir: so' o juridico; o pedido volta a pendente com o texto copiado"
      (is (= 403 (:status (chamar sec :post (str base "/parecer/substituicao") {}))))
      (let [{:keys [status corpo]} (chamar jur :post (str base "/parecer/substituicao") {})]
        (is (= 200 status))
        (is (= ["pendente" "rascunho" "Trata-se de projeto de lei."]
               [(:estado corpo) (get-in corpo [:parecer :estado]) (get-in corpo [:parecer :relatorio])])))
      (is (= 409 (:status (chamar jur :post (str base "/parecer/substituicao") {})))))
    (testing "cancelar so' o pendente, e so' a secretaria"
      (is (= 403 (:status (chamar jur :post (str base "/cancelamento") {}))))
      (is (= "cancelado" (get-in (chamar sec :post (str base "/cancelamento") {}) [:corpo :estado])))
      (is (= 409 (:status (chamar sec :post (str base "/cancelamento") {}))))
      (is (= 409 (:status (chamar jur :put (str base "/parecer") texto)))))
    (testing "pedido inexistente -> 404"
      (is (= 404 (:status (chamar jur :get (str "/legislativo/pedidos-parecer-juridico/" (random-uuid))))))
      (is (= 404 (:status (chamar jur :put (str "/legislativo/pedidos-parecer-juridico/" (random-uuid) "/parecer") texto)))))))

(deftest encaminhar-as-comissoes-e-designar-o-relator
  (let [pid (protocolar!)
        url (str "/legislativo/proposicoes/" pid "/pareceres-de-comissao")]
    (testing "a lista de comissoes da Casa"
      (is (= ["Comissão de Constituição e Justiça" "Comissão de Finanças"]
             (mapv :nome (:comissoes (:corpo (chamar sec :get "/legislativo/comissoes")))))))
    (testing "sem o rito de parecer configurado: 409 nomeado, nao 500"
      (let [{:keys [status corpo]} (chamar sec :post url {:comissoes [{:comissao-id (str ccj)}]})]
        (is (= 409 status))
        (is (= "sem-rito-de-parecer" (:causa corpo)))))
    (let [tid (random-uuid)]
      (repo/criar-template! *repo* ente {:id tid :chave "parecer_comissao_http" :versao 1 :sujeito "parecer"
                                         :nome "Parecer [FIXTURE]" :estado-inicial "aguardando_designacao"})
      (repo/criar-estado! *repo* ente {:id (random-uuid) :template-id tid :chave "aguardando_designacao"
                                       :nome "Aguardando" :terminal false}))
    (testing "validacoes: comissao inexistente, relator que nao e' vereador, repetida, vazia -> 400"
      (is (= 400 (:status (chamar sec :post url {:comissoes [{:comissao-id (str (random-uuid))}]}))))
      (is (= 400 (:status (chamar sec :post url {:comissoes [{:comissao-id (str ccj) :relator-id (str (random-uuid))}]}))))
      (is (= 400 (:status (chamar sec :post url {:comissoes [{:comissao-id (str ccj)} {:comissao-id (str ccj)}]}))))
      (is (= 400 (:status (chamar sec :post url {:comissoes []})))))
    (testing "materia inexistente -> 404; so' a secretaria encaminha"
      (is (= 404 (:status (chamar sec :post (str "/legislativo/proposicoes/" (random-uuid) "/pareceres-de-comissao")
                                  {:comissoes [{:comissao-id (str ccj)}]}))))
      (is (= 403 (:status (chamar jur :post url {:comissoes [{:comissao-id (str ccj)}]})))))
    (testing "abre um parecer por comissao, com o relator quando informado; repetir devolve o que ja' existe"
      (let [{:keys [status corpo]} (chamar sec :post url {:comissoes [{:comissao-id (str ccj) :relator-id (str ver-id)}
                                                                      {:comissao-id (str fin)}]})
            [a b] (:pareceres corpo)]
        (is (= 201 status))
        (is (= ["Comissão de Constituição e Justiça" "Ana Vereadora" false] ((juxt :comissao-nome :relator-nome :ja-existia) a)))
        (is (= ["Comissão de Finanças" nil] ((juxt :comissao-nome :relator-nome) b)))
        (is (= [true] (mapv :ja-existia (:pareceres (:corpo (chamar sec :post url {:comissoes [{:comissao-id (str ccj)}]}))))))
        (testing "designar o relator do parecer sem relator; a ficha mostra o nome"
          (let [r (chamar sec :post (str "/legislativo/pareceres/" (:id b) "/relator") {:relator-id (str outro-ver-id)})]
            (is (= 200 (:status r)))
            (is (= "Beto Vereador" (get-in r [:corpo :relator-nome]))))
          (is (= 404 (:status (chamar sec :post (str "/legislativo/pareceres/" (:id b) "/relator") {:relator-id (str (random-uuid))})))
              "vereador que nao existe nesta Casa")
          (is (= 404 (:status (chamar sec :post (str "/legislativo/pareceres/" (random-uuid) "/relator") {:relator-id (str ver-id)}))))
          (is (= 400 (:status (chamar sec :post (str "/legislativo/pareceres/" (:id b) "/relator") {}))))
          (let [ficha (:corpo (chamar sec :get (str "/legislativo/proposicoes/" pid "/ficha")))]
            (is (= #{["Comissão de Constituição e Justiça" "Ana Vereadora"] ["Comissão de Finanças" "Beto Vereador"]}
                   (set (map (juxt :comissao-nome :relator-nome) (:pareceres ficha))))))
          (testing "o relator pede o parecer juridico da materia que relata (posse)"
            (let [r (chamar ver :post (str "/meu/pareceres/" (:id a) "/pedido-juridico") {})]
              (is (= 201 (:status r)))
              (is (= ["relator" (str pid) "Vereadora Ana"]
                     [(get-in r [:corpo :origem]) (get-in r [:corpo :proposicao :id]) (get-in r [:corpo :pedido-por])])))
            (is (= 404 (:status (chamar ver :post (str "/meu/pareceres/" (:id b) "/pedido-juridico") {})))
                "parecer de OUTRO relator: 404 sem distinguir motivo")
            (is (= 403 (:status (chamar sec :post (str "/meu/pareceres/" (:id a) "/pedido-juridico") {}))))))))))
