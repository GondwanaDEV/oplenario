(ns oplenario.sessoes.publicar-pauta-http-in-test
  "INTEGRACAO (PG real + a cadeia HTTP inteira de `rotas/montar`): ADR-0019 fatia 3 — PUBLICAR A PAUTA. Sessoes e
  legislativo REAIS (os avisos saem das materias, pareceres e pedidos juridicos de verdade, pelo seam do host);
  identidade e cadastros FAKE (papeis, vereador da identidade, cargos na Mesa). Prova: a regra da Casa (padrao
  secretaria; so' o admin_ente configura), quem publica por regra (secretaria / presidente / 1o secretario / Mesa; 403
  para quem nao), o aviso de antecedencia (aceita, nao bloqueia), os avisos por materia, o 'alterada desde a
  publicacao', a republicacao com justificativa, e o portal mostrando a versao publicada (e nunca a sessao secreta)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.cadastros.components.repositorio :as repo-cadastros-comp]
            [oplenario.catalogo :as catalogo]
            [oplenario.cadastros.relacoes.cadastro :as rel-cad]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.identidade.relacoes.identidade :as rel-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.catalogo :as kcat]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.legislativo.components.repositorio-situacao :as repo-situacao]
            [oplenario.legislativo.db.parecer :as parecer]
            [oplenario.legislativo.db.parecer-tramitacao :as ptram]
            [oplenario.legislativo.db.tramitacao :as tram]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.registro-fatos :as rf]
            [oplenario.rotas :as rotas]
            [oplenario.sessoes.components.repositorio :as repo-s])
  (:import (java.time Instant LocalDate)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *leg* nil)
(def ^:dynamic *ses* nil)
(def ^:dynamic *reg* nil)
(def ^:dynamic *svc* nil)

(def agora (Instant/parse "2026-10-06T12:00:00Z"))
(def inicio (Instant/parse "2026-10-07T12:00:00Z"))   ; a sessao comeca 24 h depois de `agora`

;; as pessoas (identidades) e o cadastro de vereador de cada uma
(def sec (random-uuid))
(def adm (random-uuid))
(def pres (random-uuid))
(def prim (random-uuid))
(def vice (random-uuid))
(def comum (random-uuid))
(def licenciado (random-uuid))

(def papeis {sec #{"secretario"} adm #{"admin_ente"} pres #{"vereador" "admin_ente"} prim #{"vereador"}
             vice #{"vereador"} comum #{"vereador"} licenciado #{"vereador"}})

(def vereador-de (into {} (map (fn [i] [i (random-uuid)])) [pres prim vice comum licenciado]))

(def roster
  [{:vereador-id (vereador-de pres) :estado-mandato "vigente" :cargo-mesa "presidente"}
   {:vereador-id (vereador-de prim) :estado-mandato "vigente" :cargo-mesa "1_secretario"}
   {:vereador-id (vereador-de vice) :estado-mandato "vigente" :cargo-mesa "vice"}
   {:vereador-id (vereador-de comum) :estado-mandato "vigente" :cargo-mesa nil}
   {:vereador-id (vereador-de licenciado) :estado-mandato "licenciado" :cargo-mesa "2_secretario"}])

(def casas (atom #{}))

(defn- fake-identidade []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ id] {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis (get papeis id #{})})
    (vinculos-de [_ _ id] (when (contains? papeis id) [{:tipo "servidor"}]))
    (nome-por-id [_ id] {:nome (if (= id sec) "Marina Freire" "Vereadora Ana")})))

(defn- fake-cadastros []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cadastros-comp/RepoCadastros
    (vereador-por-identidade [_ _ id] (when-let [v (vereador-de id)] {:id v}))
    (roster-da-casa [_ _ _] roster)
    (buscar-ente [_ id] (when (contains? @casas id) {:id id :nome-oficial "Câmara de Teste"}))))

(use-fixtures :once
  (fn [t]
    (let [c   (component/start (datasource/datasource (config/carregar)))
          reg (component/start (rf/registro-fatos (merge rel-cad/relacoes rel-id/relacoes)))]
      (migracao/migrar! (:ds c))
      (let [leg (repo-leg/->RepoLegislativoPg c (outbox/bus))
            ses (repo-s/->RepoSessoesPg c (outbox/bus))]
        (binding [*ds* (:ds c) *leg* leg *ses* ses *reg* reg
                  *svc* (-> (http/servico (config/carregar)
                                          (rotas/montar {:idp (idp-dev/idp-dev) :repo-identidade (fake-identidade)
                                                         :repo-legislativo leg :repo-sessoes ses
                                                         :repo-cadastros (fake-cadastros) :registro reg
                                                         :relogio (tempo/relogio-fixo agora)})
                                          it/globais)
                            ph/create-server ::ph/service-fn)]
          (try (t) (finally (component/stop reg) (component/stop c))))))))

(defn- cab [ente quem] {"authorization" (str "Bearer " (json/write-value-as-string
                                                          {:sub "u" :ente-id (str ente) :identidade-id (str quem)}))
                        "Content-Type" "application/json"})

(defn- chamar
  ([ente quem metodo url] (chamar ente quem metodo url nil))
  ([ente quem metodo url corpo]
   (let [r (apply pt/response-for *svc* metodo url
                  (concat (when quem [:headers (cab ente quem)])
                          (when corpo [:body (json/write-value-as-string corpo)])))]
     {:status (:status r)
      :corpo (when (seq (:body r)) (json/read-value (:body r) json/keyword-keys-object-mapper))})))

;; ---------- o cenario ----------

(defn- nova-casa! [] (let [e (random-uuid)] (swap! casas conj e) e))

(defn- materia! [ente ementa]
  (:id (repo-leg/protocolar! *leg* ente {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                                         :municipio-nome "Fortaleza" :ementa ementa})))

(defn- parecer-emitido! [ente materia]
  (tenancy/com-tenant* *ds* ente
    (fn [tx]
      (let [tid (random-uuid)]
        (tram/criar-template! tx {:id tid :ente-id ente :chave (str "parecer-" tid) :versao 1 :sujeito "parecer"
                                  :nome "Parecer [FIXTURE]" :estado-inicial "com_relator"})
        (doseq [[ch term] [["com_relator" false] ["aprovado" true]]]
          (tram/criar-estado! tx {:id (random-uuid) :ente-id ente :template-id tid :chave ch :nome ch :terminal term}))
        (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid :de-estado "com_relator"
                                   :para-estado "aprovado" :gatilho "emitir" :guarda nil :ordem 1})
        (let [{pcid :id} (parecer/criar! tx {:id (random-uuid) :ente-id ente :objeto-tipo "proposicao" :objeto-id materia
                                             :comissao-id (random-uuid) :template-id tid})]
          (ptram/transicionar-parecer! tx {:registro *reg* :ente-id ente :parecer-id pcid :template-id tid
                                           :gatilho "emitir" :agora (LocalDate/parse "2026-10-01")}))))))

(defn- sessao! [ente tipo]
  (:id (repo-s/agendar-sessao! *ses* ente {:id (random-uuid) :sessao-legislativa-id (random-uuid) :tipo-sessao tipo
                                           :modalidade "presencial" :agendada-para inicio})))

(defn- item! [ente sid materia]
  (repo-s/adicionar-item-na-sessao! *ses* ente {:id (random-uuid) :sessao-id sid :fase "ordem_do_dia"
                                                :tipo-item "proposicao" :proposicao-id materia}))

(defn- url [sid] (str "/sessoes/" sid "/pauta/publicacao"))

(defn- regra! [ente quem-publica & [horas]]
  (chamar ente adm :put "/regra-da-pauta" {:quem-publica quem-publica :antecedencia-minima-horas horas}))

;; ---------- os testes ----------

(deftest a-regra-da-casa-e-do-admin
  (let [ente (nova-casa!)]
    (testing "sem configuracao: a secretaria publica, sem antecedencia"
      (is (= {:quem-publica "secretaria" :antecedencia-minima-horas nil :configurada false}
             (:corpo (chamar ente sec :get "/regra-da-pauta")))))
    (testing "so' o admin_ente configura"
      (is (= 403 (:status (chamar ente sec :put "/regra-da-pauta" {:quem-publica "mesa"}))))
      (is (= 403 (:status (chamar ente comum :put "/regra-da-pauta" {:quem-publica "mesa"}))))
      (let [{:keys [status corpo]} (regra! ente "presidente" 48)]
        (is (= 200 status))
        (is (= ["presidente" 48 true] ((juxt :quem-publica :antecedencia-minima-horas :configurada) corpo)))))
    (testing "corpo invalido -> 400 (quem publica fora do vocabulario, antecedencia fora do intervalo, campo a mais)"
      (is (= 400 (:status (regra! ente "vereador"))))
      (is (= 400 (:status (regra! ente "mesa" 0))))
      (is (= 400 (:status (chamar ente adm :put "/regra-da-pauta" {:quem-publica "mesa" :assinatura true})))))
    (testing "a Mesa le a regra"
      (is (= "presidente" (get-in (chamar ente pres :get "/regra-da-pauta") [:corpo :quem-publica]))))))

(deftest a-secretaria-publica-por-padrao-com-os-avisos-das-materias
  (let [ente (nova-casa!)
        com-parecer (materia! ente "Institui a Semana da Leitura")
        sem-parecer (materia! ente "Denomina a Rua das Flores")
        com-juridico (materia! ente "Cria o Conselho de Cultura")
        sid (sessao! ente "ordinaria")]
    (parecer-emitido! ente com-parecer)
    (parecer-emitido! ente com-juridico)
    (is (= 201 (:status (chamar ente sec :post "/legislativo/pedidos-parecer-juridico"
                                {:proposicao-id (str com-juridico)}))))
    (testing "pauta vazia: a tela diz; o ato recusa com o motivo"
      (is (= [0 true] ((juxt :itens-na-pauta :pode-publicar) (:corpo (chamar ente sec :get (url sid))))))
      (is (= [409 "pauta-vazia"] ((juxt :status (comp :motivo :corpo)) (chamar ente sec :post (url sid) {})))))
    (doseq [m [com-parecer sem-parecer com-juridico]] (item! ente sid m))
    (testing "a tela: quem pode, os avisos por materia (com a ementa), nada publicado ainda"
      (let [{:keys [status corpo]} (chamar ente sec :get (url sid))]
        (is (= 200 status))
        (is (= [true false 3 false nil] ((juxt :pode-publicar :republicacao :itens-na-pauta :avisos-indisponiveis :ultima) corpo)))
        (is (= #{["sem-parecer-comissao" (str sem-parecer)] ["pedido-juridico-pendente" (str com-juridico)]}
               (set (map (juxt :tipo :proposicao-id) (:avisos corpo)))))
        (is (= "Denomina a Rua das Flores"
               (some #(when (= (str sem-parecer) (:proposicao-id %)) (get-in % [:proposicao :ementa])) (:avisos corpo))))))
    (testing "o vereador comum le a tela mas nao publica (regra: secretaria) — 403 no ato"
      (let [c (:corpo (chamar ente comum :get (url sid)))]
        (is (= [false "Pela regra desta Casa, quem publica a pauta é a secretaria legislativa."]
               ((juxt :pode-publicar :motivo) c))))
      (is (= 403 (:status (chamar ente comum :post (url sid) {})))))
    (testing "a secretaria publica: v1, com os avisos gravados (aviso nao bloqueia)"
      (let [{:keys [status corpo]} (chamar ente sec :post (url sid) {})]
        (is (= 201 status))
        (is (= [1 "publicacao_inicial" 3] ((juxt :versao :tipo-versao :itens) corpo)))
        (is (= 2 (count (:avisos corpo))))
        (is (nil? (:aviso corpo)) "a Casa nao tem regra de antecedencia")))
    (testing "a pauta viva (a TV) mostra a oficial: v1, nao alterada"
      (is (= {:versao 1 :alterada-desde false}
             (select-keys (get-in (chamar ente sec :get (str "/sessoes/" sid "/pauta")) [:corpo :publicacao])
                          [:versao :alterada-desde]))))
    (testing "sem mudanca, nao ha' o que republicar"
      (is (= "sem-alteracao" (get-in (chamar ente sec :post (url sid) {:justificativa "de novo"}) [:corpo :motivo]))))
    (item! ente sid (materia! ente "Nova materia"))
    (testing "alterada desde a publicacao v1: na pauta viva e na tela"
      (is (true? (get-in (chamar ente sec :get (str "/sessoes/" sid "/pauta")) [:corpo :publicacao :alterada-desde])))
      (let [c (:corpo (chamar ente sec :get (url sid)))]
        (is (= [true true 1] ((juxt :alterada-desde-a-publicacao :republicacao (comp :versao :ultima)) c)))
        (is (= "Marina Freire" (get-in c [:ultima :publicada-por-nome])))
        (is (= "secretaria" (get-in c [:ultima :a-titulo])))))
    (testing "republicar exige justificativa"
      (is (= [409 "justificativa-obrigatoria"] ((juxt :status (comp :motivo :corpo)) (chamar ente sec :post (url sid) {}))))
      (let [{:keys [status corpo]} (chamar ente sec :post (url sid) {:justificativa "Incluída a nova matéria"})]
        (is (= [201 2 "republicacao"] [status (:versao corpo) (:tipo-versao corpo)]))))
    (testing "o historico: as duas versoes, a mais recente primeiro"
      (is (= [2 1] (mapv :versao (:versoes (:corpo (chamar ente sec :get (url sid))))))))
    (testing "corpo invalido -> 400; sessao que nao existe -> 404"
      (is (= 400 (:status (chamar ente sec :post (url sid) {:justificativa "x" :a-titulo "presidente"}))))
      (is (= 404 (:status (chamar ente sec :post (url (random-uuid)) {}))))
      (is (= 404 (:status (chamar ente sec :get (url (random-uuid)))))))))

(deftest quem-publica-e-a-regra-da-casa
  (let [ente (nova-casa!)
        sid (sessao! ente "ordinaria")
        pode? (fn [quem] (get-in (chamar ente quem :get (url sid)) [:corpo :pode-publicar]))]
    (item! ente sid (materia! ente "Materia A"))
    (testing "presidente: so' quem tem o cargo de presidente na Mesa vigente — a secretaria nao"
      (regra! ente "presidente")
      (is (= [true false false false] (mapv pode? [pres prim sec comum])))
      (is (= 403 (:status (chamar ente sec :post (url sid) {}))))
      (is (= 403 (:status (chamar ente prim :post (url sid) {}))))
      (let [{:keys [status corpo]} (chamar ente pres :post (url sid) {})]
        (is (= 201 status))
        (is (= 1 (:versao corpo))))
      (is (= "presidente" (get-in (chamar ente sec :get (url sid)) [:corpo :ultima :a-titulo]))))
    (testing "primeiro_secretario: o 1o Secretario, e nao o Presidente"
      (regra! ente "primeiro_secretario")
      (is (= [false true false] (mapv pode? [pres prim vice])))
      (item! ente sid (materia! ente "Materia B"))
      (is (= 403 (:status (chamar ente pres :post (url sid) {:justificativa "B"}))))
      (is (= 201 (:status (chamar ente prim :post (url sid) {:justificativa "B"})))))
    (testing "mesa: qualquer membro com cargo na Mesa vigente; o licenciado e o vereador sem cargo nao"
      (regra! ente "mesa")
      (is (= [true true true false false false] (mapv pode? [pres prim vice comum licenciado sec])))
      (item! ente sid (materia! ente "Materia C"))
      (is (= 403 (:status (chamar ente licenciado :post (url sid) {:justificativa "C"}))))
      (is (= 201 (:status (chamar ente vice :post (url sid) {:justificativa "C"})))))
    (testing "o admin_ente sem papel de secretaria/vereador nem ve a tela (gate grosso)"
      (is (= 403 (:status (chamar ente adm :get (url sid))))))))

(deftest antecedencia-e-aviso-nao-bloqueio
  (let [ente (nova-casa!)
        sid (sessao! ente "ordinaria")]
    (item! ente sid (materia! ente "Materia com prazo"))
    (regra! ente "secretaria" 48)
    (testing "a tela ja' mostra: 24 h reais contra 48 h da Casa"
      (let [c (:corpo (chamar ente sec :get (url sid)))]
        (is (= {:minimo-horas 48 :horas-reais 24 :cumprida false} (:antecedencia c)))
        (is (some #(= {:tipo "antecedencia-nao-cumprida" :minimo-horas 48 :horas-reais 24} %) (:avisos c)))))
    (testing "a publicacao e' ACEITA, com o aviso no retorno"
      (let [{:keys [status corpo]} (chamar ente sec :post (url sid) {})]
        (is (= 201 status))
        (is (= "antecedencia-nao-cumprida" (:aviso corpo)))
        (is (= {:minimo-horas 48 :horas-reais 24 :cumprida false} (:antecedencia corpo)))))
    (testing "dentro da antecedencia: sem aviso"
      (regra! ente "secretaria" 12)
      (let [sid2 (sessao! ente "ordinaria")]
        (item! ente sid2 (materia! ente "Outra materia"))
        (let [{:keys [status corpo]} (chamar ente sec :post (url sid2) {})]
          (is (= 201 status))
          (is (nil? (:aviso corpo)))
          (is (true? (get-in corpo [:antecedencia :cumprida]))))))))

(deftest o-portal-mostra-a-pauta-oficial
  (let [ente (nova-casa!)
        publica (sessao! ente "ordinaria")
        ainda-nao (sessao! ente "ordinaria")
        secreta (sessao! ente "secreta")
        m (materia! ente "Institui o Programa de Hortas")]
    (item! ente publica m)
    (item! ente secreta (materia! ente "Materia sigilosa"))
    (chamar ente sec :post (url publica) {})
    (chamar ente sec :post (url secreta) {})
    (item! ente publica (materia! ente "Incluida depois, nao publicada"))
    (testing "a lista: a publicada com a versao; a agendada sem publicacao; nunca a secreta"
      (let [{:keys [status corpo]} (chamar ente nil :get (str "/portal/casa/" ente "/pautas"))
            por-id (into {} (map (juxt :sessao-id identity)) (:sessoes corpo))]
        (is (= 200 status))
        (is (= #{(str publica) (str ainda-nao)} (set (keys por-id))))
        (is (= [1 1] ((juxt :versao :itens) (:pauta-oficial (por-id (str publica))))))
        (is (nil? (:pauta-oficial (por-id (str ainda-nao)))))))
    (testing "a pauta oficial e' a VERSAO PUBLICADA, nao a pauta viva (o item incluido depois nao aparece)"
      (let [{:keys [status corpo]} (chamar ente nil :get (str "/portal/casa/" ente "/pautas/" publica))]
        (is (= 200 status))
        (is (= [1 "publicacao_inicial"] ((juxt :versao :tipo-versao) (:vigente corpo))))
        (is (= [(str m)] (mapv :proposicao-id (get-in corpo [:vigente :itens]))))
        (is (= "Institui o Programa de Hortas" (get-in corpo [:vigente :itens 0 :proposicao :ementa])))
        (is (not-any? #(contains? % :lock-version) (get-in corpo [:vigente :itens])))))
    (testing "'pauta ainda nao publicada' e' 200 sem vigente; secreta e Casa inexistente sao 404"
      (let [{:keys [status corpo]} (chamar ente nil :get (str "/portal/casa/" ente "/pautas/" ainda-nao))]
        (is (= 200 status))
        (is (nil? (:vigente corpo))))
      (is (= 404 (:status (chamar ente nil :get (str "/portal/casa/" ente "/pautas/" secreta)))))
      (is (= 404 (:status (chamar ente nil :get (str "/portal/casa/" (random-uuid) "/pautas")))))
      (is (= 400 (:status (chamar ente nil :get "/portal/casa/nao-e-uuid/pautas")))))))

(deftest publicar-pauta-e-acao-do-catalogo
  ;; ADR-0012: o agente PROPOE `publicar_pauta`; o que a pessoa le antes de confirmar traz os avisos, e a confirmacao
  ;; roda a MESMA acao como ela (aqui, o ator sem `:via` = a tela confirmando).
  (let [ente (nova-casa!)
        sid (sessao! ente "extraordinaria")
        e (get catalogo/por-nome "publicar_pauta")
        ator {:ente-id ente :identidade-id sec :papeis #{"secretario"}}
        deps {:repo-sessoes *ses* :relogio (tempo/relogio-fixo agora)
              :situacao-de-parecer (fn [en ids] (repo-situacao/situacao-de-parecer-das-materias *leg* en ids))
              :resumir-proposicoes (fn [en ids] (repo-leg/resumos-de-proposicoes *leg* en ids))}]
    (item! ente sid (materia! ente "Materia sem parecer"))
    (is (= [:ato :confirmar] ((juxt :classe :ritual) e)))
    (let [{:keys [titulo texto]} ((:apresentar e) deps ator {:sessao-id sid})]
      (is (re-find #"sessão extraordinária nº \d+" titulo))
      (is (re-find #"1 aviso\(s\) sobre matérias: sem parecer da comissão" texto))
      (is (re-find #"Os avisos não impedem a publicação" texto)))
    (let [saida (kcat/executar e deps ator {"sessao-id" (str sid)})]
      (is (= [1 "publicacao_inicial" 1] ((juxt :versao :tipo-versao :itens) saida))))
    (testing "a recusa do ato chega como conflito legivel da proposta"
      (is (= :proposta/ato-recusado
             (try (kcat/executar e deps ator {"sessao-id" (str sid)}) nil
                  (catch clojure.lang.ExceptionInfo ex (:tipo (ex-data ex)))))))
    (testing "o vereador sem cargo nao publica nem pela proposta (a regra roda na confirmacao)"
      (is (thrown? clojure.lang.ExceptionInfo
                   (kcat/executar e deps {:ente-id ente :identidade-id comum :papeis #{"vereador"}}
                                  {"sessao-id" (str sid)}))))))
