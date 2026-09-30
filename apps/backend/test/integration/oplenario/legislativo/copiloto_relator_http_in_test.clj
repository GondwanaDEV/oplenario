(ns oplenario.legislativo.copiloto-relator-http-in-test
  "INTEGRACAO (PG real + a cadeia HTTP inteira de `rotas/montar`): ADR-0019 fatia 2 (Eixo 5) — o copiloto do relator e o
  relator salvando o proprio parecer. Legislativo REAL (materia protocolada com texto, pareceres abertos as comissoes com
  relator); identidade, cadastros, normas e o satelite FAKE. Prova: a posse do relator (404 sem distinguir, sem chamar a
  IA), o gate de papel das duas bordas, o que o core manda a IA (texto publico da materia, comissao, se a Casa publicou
  normas), a conferencia do que volta, a IA fora -> 503 R-IA-1, e o PATCH do relator (nova versao 'rascunho')."
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
            [oplenario.integracao-ia.diplomat.http.out :as plataforma-ia]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.legislativo.components.repositorio-juridico :as juridico]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.registro-fatos :as rf]
            [oplenario.normas.components.repositorio :as repo-normas]
            [oplenario.rotas :as rotas])
  (:import (java.time Instant)))

(def ^:dynamic *repo* nil)
(def ^:dynamic *ds-comp* nil)
(def ^:dynamic *reg* nil)

(def ente (random-uuid))
(def sec (random-uuid))
(def ver (random-uuid))
(def outro-ver (random-uuid))
(def ver-id (random-uuid))
(def outro-ver-id (random-uuid))
(def ccj (random-uuid))
(def fin (random-uuid))

(def papeis {sec #{"secretario"} ver #{"vereador"} outro-ver #{"vereador"}})

(defn- fake-identidade []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ id] {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis (get papeis id #{})})))

(defn- fake-cadastros []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cadastros-comp/RepoCadastros
    (vereador-por-identidade [_ _ id] (condp = id ver {:id ver-id} outro-ver {:id outro-ver-id} nil))
    (nomes-de-comissoes [_ _ ids]
      (into {} (keep (fn [i] (condp = i ccj [i "Comissão de Constituição e Justiça"] fin [i "Comissão de Finanças"] nil))) ids))))

(defn- fake-normas
  "A Casa com a LOM vigente (`publicadas? true`) ou so' com uma norma de REFERENCIA (federal, sem Casa) vigente."
  [publicadas?]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-normas/RepoNormas
    (listar-normas [_ e]
      (cond-> [{:id (random-uuid) :ente-id nil :camada "federal" :vigente {:id (random-uuid)}}]
        publicadas? (conj {:id (random-uuid) :ente-id e :camada "municipal" :especie "lei_organica"
                           :vigente {:id (random-uuid)}})))))

(defn- fake-ia
  "O satelite: `resposta` e' o corpo que ele devolve (ou uma ex-info a lancar); cada pedido vai para `pedidos`."
  [pedidos resposta]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify plataforma-ia/PlataformaIA
    (rascunhar-analise-parecer [_ e p]
      (swap! pedidos conj [e p])
      (if (instance? Throwable resposta) (throw resposta) resposta))))

(use-fixtures :once
  (fn [t]
    (let [c   (component/start (datasource/datasource (config/carregar)))
          reg (component/start (rf/registro-fatos (merge rel-cad/relacoes rel-id/relacoes)))]
      (migracao/migrar! (:ds c))
      (binding [*repo* (repo/->RepoLegislativoPg c (outbox/bus)) *ds-comp* c *reg* reg]
        (try (t) (finally (component/stop reg) (component/stop c)))))))

(defn- servico [ia publicadas?]
  (-> (http/servico (config/carregar)
                    (rotas/montar (cond-> {:idp (idp-dev/idp-dev) :repo-identidade (fake-identidade)
                                           :repo-legislativo *repo* :repo-cadastros (fake-cadastros)
                                           :repo-normas (fake-normas publicadas?)
                                           :registro *reg*
                                           :relogio (tempo/relogio-fixo (Instant/parse "2026-09-30T12:00:00Z"))}
                                    ia (assoc :plataforma-ia ia)))
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- chamar
  ([svc quem metodo url] (chamar svc quem metodo url nil))
  ([svc quem metodo url corpo]
   (let [r (apply pt/response-for svc metodo url
                  :headers {"authorization" (str "Bearer " (json/write-value-as-string
                                                              {:sub "u" :ente-id (str ente) :identidade-id (str quem)}))
                            "Content-Type" "application/json"}
                  (when corpo [:body (json/write-value-as-string corpo)]))]
     {:status (:status r)
      :corpo (when (seq (:body r)) (json/read-value (:body r) json/keyword-keys-object-mapper))})))

(def ^:private texto-materia "Art. 1º Fica instituído o Programa Municipal de Hortas Comunitárias.")

(defn- materia-com-pareceres!
  "Uma materia protocolada com texto e dois pareceres de comissao: CCJ (relator `ver`) e Financas (relator `outro-ver`)."
  []
  (let [pid (:id (repo/protocolar! *repo* ente {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                                                :municipio-nome "Fortaleza" :ementa "Institui o Programa de Hortas"
                                                :autor-texto "Vereadora Ana" :texto texto-materia}))
        tid (random-uuid)]
    (repo/criar-template! *repo* ente {:id tid :chave (str "parecer_copiloto_" tid) :versao 1 :sujeito "parecer"
                                       :nome "Parecer [FIXTURE]" :estado-inicial "aguardando_designacao"})
    (repo/criar-estado! *repo* ente {:id (random-uuid) :template-id tid :chave "aguardando_designacao"
                                     :nome "Aguardando" :terminal false})
    (let [[a b] (juridico/abrir-pareceres-de-comissao! *repo* ente {:proposicao-id pid :created-by sec
                                                                    :comissoes [{:comissao-id ccj :relator-id ver-id}
                                                                                {:comissao-id fin :relator-id outro-ver-id}]})]
      {:pid pid :ccj (:id a) :fin (:id b)})))

(defn- resposta-boa [pid]
  {:analise {:execucao-id "e1"
             :texto (str "A proposição tem por objeto hortas [[materia:" pid " | Institui o Programa de Hortas]].\n\n"
                         "Compete ao Município legislar [[norma:n1#art11 | Compete ao Município legislar]]. "
                         "[confirmar: se a iniciativa é do Prefeito]")
             :citacoes [{:fonte-id (str "materia:" pid) :rotulo "Projeto de Lei nº 1/2026"
                         :trecho "Institui o Programa de Hortas" :status "conferida"}
                        {:fonte-id "norma:n1#art11" :rotulo "Lei Orgânica do Município, art. 11 (consolidada até 30/06/2026)"
                         :trecho "Compete ao Município legislar" :status "conferida"}
                        {:fonte-id (str "materia:" (random-uuid)) :rotulo "forjada" :trecho "x" :status "conferida"}]
             :paragrafos-sem-fonte [] :pontos-a-confirmar ["FORJADO"] :incerteza "normal"
             :motivos-incerteza [] :modelo "fake-1" :contaminado false}
   :normas "citadas" :indisponivel nil})

(deftest o-relator-pede-o-rascunho-da-analise
  (let [{:keys [pid ccj fin]} (materia-com-pareceres!)
        pedidos (atom [])
        svc (servico (fake-ia pedidos (resposta-boa pid)) true)]
    (testing "o relator do parecer recebe o rascunho conferido"
      (let [{:keys [status corpo]} (chamar svc ver :post (str "/meu/pareceres/" ccj "/copiloto"))
            a (:analise corpo)]
        (is (= 200 status))
        (is (not (str/includes? (:texto a) "[[")) "o campo Analise recebe o texto limpo")
        (is (str/includes? (:texto a) "Compete ao Município legislar."))
        (is (= ["se a iniciativa é do Prefeito"] (:pontos-a-confirmar a)))
        (is (= [(str "materia:" pid) "norma:n1#art11"] (mapv :fonte-id (:citacoes a)))
            "a citacao de OUTRA materia cai")
        (is (= ["citadas" "normal" nil] [(:normas corpo) (:incerteza a) (:indisponivel corpo)]))))
    (testing "o que o core manda a IA: o texto publico da materia, a comissao e as normas da Casa"
      (let [[e p] (first @pedidos)]
        (is (= ente e))
        (is (= [(str pid) "projeto_lei" "Institui o Programa de Hortas" texto-materia "Vereadora Ana"
                "Comissão de Constituição e Justiça" true]
               ((juxt :proposicao_id :tipo :ementa :texto :autor_texto :comissao :normas_publicadas) p)))))
    (testing "parecer de OUTRO relator, ou inexistente: 404 sem distinguir, e a IA nem e' chamada"
      (reset! pedidos [])
      (is (= 404 (:status (chamar svc ver :post (str "/meu/pareceres/" fin "/copiloto")))))
      (is (= 404 (:status (chamar svc ver :post (str "/meu/pareceres/" (random-uuid) "/copiloto")))))
      (is (empty? @pedidos)))
    (testing "a borda do vereador nao e' da secretaria, e vice-versa"
      (is (= 403 (:status (chamar svc sec :post (str "/meu/pareceres/" ccj "/copiloto")))))
      (is (= 403 (:status (chamar svc ver :post (str "/legislativo/pareceres/" ccj "/copiloto"))))))
    (testing "a secretaria pede no editor dela, para qualquer parecer da Casa"
      (let [{:keys [status corpo]} (chamar svc sec :post (str "/legislativo/pareceres/" fin "/copiloto"))]
        (is (= 200 status))
        (is (= "Comissão de Finanças" (:comissao (second (last @pedidos)))))
        (is (= "citadas" (:normas corpo))))
      (is (= 404 (:status (chamar svc sec :post (str "/legislativo/pareceres/" (random-uuid) "/copiloto"))))))))

(deftest sem-normas-publicadas-a-casa-e-avisada
  (let [{:keys [pid ccj]} (materia-com-pareceres!)
        pedidos (atom [])
        svc (servico (fake-ia pedidos (resposta-boa pid)) false)
        {:keys [status corpo]} (chamar svc ver :post (str "/meu/pareceres/" ccj "/copiloto"))]
    (is (= 200 status))
    (is (false? (:normas_publicadas (second (first @pedidos))))
        "so' a norma de referencia federal vigente: a Casa nao publicou LOM nem Regimento")
    (is (= ["sem-normas" "revisar_com_atencao"] [(:normas corpo) (get-in corpo [:analise :incerteza])]))))

(deftest ia-fora-e-r-ia-1
  (let [{:keys [ccj]} (materia-com-pareceres!)]
    (testing "o satelite nao responde: 503 com a mensagem da tela, nunca 500"
      (let [svc (servico (fake-ia (atom []) (ex-info "fora" {:tipo :ia/indisponivel :motivo "timeout"})) true)
            {:keys [status corpo]} (chamar svc ver :post (str "/meu/pareceres/" ccj "/copiloto"))]
        (is (= 503 status))
        (is (re-find #"Redija a análise pelo editor" (:erro corpo)))))
    (testing "sem integracao configurada (o cliente HTTP real, sem url): 503 tambem"
      (is (= 503 (:status (chamar (servico nil true) sec :post (str "/legislativo/pareceres/" ccj "/copiloto"))))))
    (testing "a IA respondeu sem rascunho (cota da Casa): 200 sem analise, com a mensagem"
      (let [svc (servico (fake-ia (atom []) {:analise nil :normas "citadas"
                                             :indisponivel {:motivo "cota" :mensagem "A IA da Casa atingiu o limite"}})
                         true)
            {:keys [status corpo]} (chamar svc ver :post (str "/meu/pareceres/" ccj "/copiloto"))]
        (is (= 200 status))
        (is (= [nil "A IA da Casa atingiu o limite"] [(:analise corpo) (:indisponivel corpo)]))))))

(deftest o-relator-salva-o-texto-do-proprio-parecer
  (let [{:keys [ccj fin]} (materia-com-pareceres!)
        svc (servico nil true)
        corpo {:relatorio "Trata-se de projeto de lei." :analise "Compete ao Município legislar."}]
    (let [{:keys [status corpo]} (chamar svc ver :patch (str "/meu/pareceres/" ccj) corpo)]
      (is (= 200 status))
      (is (= ["rascunho" "Trata-se de projeto de lei." "Compete ao Município legislar."]
             ((juxt :texto-estado :relatorio :analise) corpo))))
    (is (= "Compete ao Município legislar." (get-in (chamar svc ver :get (str "/meu/pareceres/" ccj)) [:corpo :analise]))
        "o editor do relator reabre com o que ele salvou")
    (is (= "Compete ao Município legislar." (get-in (chamar svc sec :get (str "/legislativo/pareceres/" ccj)) [:corpo :analise]))
        "a secretaria ve' a mesma versao (um texto so' por parecer)")
    (is (= 404 (:status (chamar svc ver :patch (str "/meu/pareceres/" fin) corpo))) "parecer de outro relator")
    (is (= 403 (:status (chamar svc sec :patch (str "/meu/pareceres/" ccj) corpo))))
    (is (= 400 (:status (chamar svc ver :patch (str "/meu/pareceres/" ccj) {:relatorio 42}))) "texto que nao e' texto")))
