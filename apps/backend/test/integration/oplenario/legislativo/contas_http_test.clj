(ns oplenario.legislativo.contas-http-test
  "INTEGRACAO (PG real + a cadeia HTTP inteira de `rotas/montar`): o julgamento das contas (ADR-0021 Parte B). Legislativo
  REAL (prestacao, PDL, votacao); identidade, cadastros e sessoes FAKE (papeis, comissoes vigentes, roster de 9
  vereadores, uma sessao aberta). Prova: o registro protocola o PDL na mesma tx; a RLS separa as Casas; a notificacao
  congela o prazo; a defesa entra por documento; a pauta recusa (409) antes e aceita depois; a votacao com quorum
  errado e' 422; o encerramento grava o resultado; o portal so' serve os documentos do TCE; so' o admin_ente muda os
  prazos."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.components.repositorio :as repo-cad]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.arquivo :as arquivo]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.components.objeto-store :as store]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.registro-fatos :as rf]
            [oplenario.rotas :as rotas]
            [oplenario.sessoes.components.repositorio :as repo-sessoes])
  (:import (java.time Instant)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *svc* nil)

;; "hoje" da Casa: 03/10/2026 (meio-dia em Fortaleza)
(def agora (Instant/parse "2026-10-03T15:00:00Z"))

(def sec (random-uuid))
(def ver (random-uuid))
(def jur (random-uuid))
(def adm (random-uuid))
(def papeis {sec #{"secretario"} ver #{"vereador"} jur #{"juridico"} adm #{"admin_ente"}})

(def comissao-financas (random-uuid))
(def roster (vec (repeatedly 9 random-uuid)))       ; 9 membros: 2/3 = 6

(def itens-de-pauta (atom []))

(defn- fake-identidade []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ id] {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis (get papeis id #{})})))

(defn- fake-cadastros []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cad/RepoCadastros
    (uf-e-municipio [_ _] {:uf "CE" :municipio-nome "Baturité"})
    (comissoes-vigentes [_ _ _] [{:id comissao-financas :nome "Comissão de Finanças e Orçamento"}])
    (nomes-de-comissoes [_ _ ids] (into {} (keep #(when (= % comissao-financas) [% "Comissão de Finanças e Orçamento"])) ids))
    (membros-da-casa [_ _ _] (count roster))
    (roster-da-casa [_ _ _] (mapv (fn [v] {:vereador-id v :estado-mandato "vigente"}) roster))))

(defn- fake-sessoes []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ ente-id id] {:id id :ente-id ente-id :estado "aberta" :transmite-publica true})
    (adicionar-item-na-sessao! [_ _ m] (swap! itens-de-pauta conj m) {:id (:id m) :ordem (count @itens-de-pauta)})))

(defn- objeto-store-memoria []
  (let [m (atom {})]
    #_{:clj-kondo/ignore [:missing-protocol-method]}
    (reify store/ObjetoStore
      (guardar! [_ k b _] (swap! m assoc k b) k)
      (abrir [_ k] (some-> (get @m k) java.io.ByteArrayInputStream.))
      (remover! [_ k] (swap! m dissoc k) nil))))

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))
          reg (component/start (rf/registro-fatos {}))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)
                *svc* (-> (http/servico (config/carregar)
                                        (rotas/montar {:idp (idp-dev/idp-dev) :repo-identidade (fake-identidade)
                                                       :repo-legislativo (repo/->RepoLegislativoPg c (outbox/bus))
                                                       :repo-cadastros (fake-cadastros) :repo-sessoes (fake-sessoes)
                                                       :objeto-store (objeto-store-memoria) :registro-fatos reg
                                                       :info-ente (constantly {:nome-oficial "Câmara"})
                                                       :relogio (tempo/relogio-fixo agora)})
                                        it/globais)
                          ph/create-server ::ph/service-fn)]
        (try (t) (finally (component/stop reg) (component/stop c)))))))

(defn- cab [ente quem]
  {"authorization" (str "Bearer " (json/write-value-as-string {:sub "u" :ente-id (str ente) :identidade-id (str quem)}))
   "Content-Type" "application/json"})

(defn- ler [r]
  (when (and (seq (:body r)) (str/starts-with? (str (get-in r [:headers "Content-Type"])) "application/json"))
    (json/read-value (:body r) json/keyword-keys-object-mapper)))

(defn- chamar
  ([ente quem metodo url] (chamar ente quem metodo url nil))
  ([ente quem metodo url corpo]
   (let [r (apply pt/response-for *svc* metodo url :headers (cab ente quem)
                  (when corpo [:body (json/write-value-as-string corpo)]))]
     {:status (:status r) :corpo (ler r) :headers (:headers r) :bruto (:body r)})))

(defn- publico [metodo url]
  (let [r (pt/response-for *svc* metodo url)]
    {:status (:status r) :corpo (ler r) :bruto (:body r)}))

(def ^:private fronteira "----contas-teste")

(defn- enviar-documento [ente quem prestacao-id tipo nome conteudo & {:keys [tipo-midia]}]
  (let [corpo (str "--" fronteira "\r\n"
                   "Content-Disposition: form-data; name=\"arquivo\"; filename=\"" nome "\"\r\n"
                   "Content-Type: " (or tipo-midia "application/pdf") "\r\n\r\n"
                   conteudo "\r\n"
                   "--" fronteira "--\r\n")
        r (pt/response-for *svc* :post (str "/contas/" prestacao-id "/documentos?tipo=" tipo)
                           :headers (assoc (cab ente quem) "Content-Type" (str "multipart/form-data; boundary=" fronteira))
                           :body corpo)]
    {:status (:status r) :corpo (ler r)}))

(def ^:private corpo-governo
  {:tipo "governo_prefeito" :exercicio 2024 :responsavel "Francisco das Chagas" :recebida-em "2026-08-01"
   :processo-tce "07345/2025-1" :parecer-previo "favoravel_com_ressalvas" :comissao-autora-id (str comissao-financas)})

(defn- registrar! [ente]
  (chamar ente sec :post "/contas" corpo-governo))

(deftest registrar-protocola-o-pdl-na-mesma-transacao
  (let [ente (random-uuid)
        {:keys [status corpo]} (registrar! ente)
        pdl-id (get-in corpo [:proposicao :id])]
    (testing "201 com a ficha: estado aguardando notificacao, prazo de julgamento congelado (60 dias)"
      (is (= 201 status))
      (is (= "aguardando_notificacao" (:estado corpo)))
      (is (= "2026-09-30" (:prazo-julgamento-ate corpo)))
      (is (= {:base-membros 9 :necessarios-para-rejeitar 6} (:quorum corpo)))
      (is (false? (:pautavel corpo)))
      (is (= "O responsável ainda não foi notificado." (:motivo-nao-pautavel corpo)))
      (is (re-matches #"PDL \d{3}/2026" (get-in corpo [:proposicao :rotulo]))))
    (testing "o PDL existe, de autoria da comissao, com a ementa do exercicio"
      (let [p (tenancy/com-tenant* *ds* ente
                (fn [tx] (jdbc/execute-one! tx ["select tipo, autor_tipo, autor_texto, ementa from legislativo.proposicoes
                                                  where ente_id = ? and id = ?::uuid" ente pdl-id])))]
        (is (= "projeto_decreto_legislativo" (:proposicoes/tipo p)))
        (is (= "comissao" (:proposicoes/autor_tipo p)))
        (is (= "Comissão de Finanças e Orçamento" (:proposicoes/autor_texto p)))
        (is (= "Dispõe sobre o julgamento das contas do Prefeito Municipal relativas ao exercício de 2024."
               (:proposicoes/ementa p)))))
    (testing "o mesmo exercicio de novo -> 409, e nenhum PDL a mais (a tx inteira volta)"
      (let [antes (tenancy/com-tenant* *ds* ente
                    #(:count (jdbc/execute-one! % ["select count(*) from legislativo.proposicoes where ente_id = ?" ente])))]
        (is (= 409 (:status (registrar! ente))))
        (is (= antes (tenancy/com-tenant* *ds* ente
                       #(:count (jdbc/execute-one! % ["select count(*) from legislativo.proposicoes where ente_id = ?" ente])))))))
    (testing "governo sem parecer ou com comissao que nao e' da Casa -> 400"
      (is (= 400 (:status (chamar ente sec :post "/contas" (assoc corpo-governo :exercicio 2023 :parecer-previo nil)))))
      (is (= 400 (:status (chamar ente sec :post "/contas"
                                  (assoc corpo-governo :exercicio 2023 :comissao-autora-id (str (random-uuid))))))))
    (testing "so' a secretaria registra"
      (is (= 403 (:status (chamar ente ver :post "/contas" (assoc corpo-governo :exercicio 2022))))))
    (testing "a Mesa: sem parecer obrigatorio, sem PDL, so' acompanhamento"
      (let [{:keys [status corpo]} (chamar ente sec :post "/contas"
                                           {:tipo "gestao_camara" :exercicio 2024 :responsavel "Mesa Diretora 2023-2024"
                                            :recebida-em "2026-07-15" :situacao-tce "Em instrução no TCE"})]
        (is (= 201 status))
        (is (= "acompanhamento" (:estado corpo)))
        (is (nil? (:proposicao corpo)))
        (is (= 409 (:status (chamar ente sec :post (str "/contas/" (:id corpo) "/notificacao")
                                    {:notificado-em "2026-10-01" :meio "Ofício"}))))))
    (testing "PATCH corrige o processo e a situacao no TCE (so' as chaves presentes)"
      (let [r (chamar ente sec :patch (str "/contas/" (:id corpo)) {:situacao-tce "Julgada regular com ressalvas"})]
        (is (= 200 (:status r)))
        (is (= "Julgada regular com ressalvas" (get-in r [:corpo :situacao-tce])))
        (is (= "07345/2025-1" (get-in r [:corpo :processo-tce]))))
      (is (= 400 (:status (chamar ente sec :patch (str "/contas/" (:id corpo)) {}))))
      (is (= 403 (:status (chamar ente ver :patch (str "/contas/" (:id corpo)) {:processo-tce "x"})))))
    (testing "a lista mostra as duas; o vereador e o juridico leem"
      (is (= 2 (count (get-in (chamar ente ver :get "/contas") [:corpo :prestacoes]))))
      (is (= 200 (:status (chamar ente jur :get "/contas")))))))

(deftest rls-entre-casas
  (let [a (random-uuid) b (random-uuid)
        id (get-in (registrar! a) [:corpo :id])]
    (is (= 200 (:status (chamar a sec :get (str "/contas/" id)))))
    (is (= 404 (:status (chamar b sec :get (str "/contas/" id)))) "a Casa B nao ve a prestacao da Casa A")
    (is (empty? (get-in (chamar b sec :get "/contas") [:corpo :prestacoes])))
    (is (= 404 (:status (chamar b sec :post (str "/contas/" id "/notificacao") {:notificado-em "2026-10-01" :meio "Ofício"}))))))

(deftest notificacao-congela-o-prazo-e-a-pauta-espera
  (let [ente (random-uuid)
        p (:corpo (registrar! ente))
        id (:id p)
        pdl (get-in p [:proposicao :id])
        incluir #(chamar ente sec :post (str "/sessoes/" (random-uuid) "/pauta/itens")
                         {:fase "ordem_do_dia" :tipo-item "proposicao" :proposicao-id pdl})]
    (testing "antes da notificacao a pauta recusa com o motivo"
      (let [r (incluir)]
        (is (= 409 (:status r)))
        (is (= "O responsável ainda não foi notificado." (get-in r [:corpo :erro])))))
    (testing "a notificacao congela o prazo de defesa (padrao 15 dias)"
      (let [{:keys [status corpo]} (chamar ente sec :post (str "/contas/" id "/notificacao")
                                           {:notificado-em "2026-10-01" :meio "Ofício entregue em mãos"})]
        (is (= 200 status))
        (is (= "prazo_de_defesa" (:estado corpo)))
        (is (= "2026-10-16" (:prazo-defesa-ate corpo)))
        (is (= "O prazo de defesa do responsável vai até 16/10/2026." (:motivo-nao-pautavel corpo)))))
    (testing "mudar o parametro depois nao mexe no prazo ja' correndo; notificar de novo -> 409"
      (is (= 200 (:status (chamar ente adm :put "/parametros-de-contas" {:prazo-defesa-dias 30 :prazo-julgamento-dias 90}))))
      (is (= "2026-10-16" (get-in (chamar ente sec :get (str "/contas/" id)) [:corpo :prazo-defesa-ate])))
      (is (= 409 (:status (chamar ente sec :post (str "/contas/" id "/notificacao")
                                  {:notificado-em "2026-10-02" :meio "Ofício"})))))
    (testing "dentro do prazo a pauta recusa com a data"
      (let [r (incluir)]
        (is (= 409 (:status r)))
        (is (= "O prazo de defesa do responsável vai até 16/10/2026." (get-in r [:corpo :erro])))))
    (testing "a defesa juntada por documento libera a pauta"
      (let [r (enviar-documento ente sec id "defesa" "defesa-escrita.pdf" "%PDF-1.4 defesa")]
        (is (= 201 (:status r)))
        (is (= "defesa" (get-in r [:corpo :tipo]))))
      (let [f (:corpo (chamar ente sec :get (str "/contas/" id)))]
        (is (= "pronta_para_pauta" (:estado f)))
        (is (true? (:pautavel f)))
        (is (some? (:defesa-juntada-em f))))
      (is (= 201 (:status (incluir))) "agora a pauta aceita o PDL"))
    (testing "o painel de votacao acha a prestacao pela materia; materia comum -> 404"
      (is (= id (get-in (chamar ente ver :get (str "/contas-da-proposicao/" pdl)) [:corpo :id])))
      (is (= 404 (:status (chamar ente ver :get (str "/contas-da-proposicao/" (random-uuid)))))))))

(deftest votacao-de-contas-exige-a-regra-e-o-encerramento-julga
  (let [ente (random-uuid)
        p (:corpo (registrar! ente))
        pdl (get-in p [:proposicao :id])
        sessao (random-uuid)
        abrir #(chamar ente sec :post (str "/sessoes/" sessao "/votacoes")
                       {:objeto-tipo "proposicao" :objeto-id pdl :modalidade %1 :quorum-tipo %2})]
    (testing "quorum ou modalidade fora da regra -> 422 com a regra"
      (let [r (abrir "nominal" "maioria_simples")]
        (is (= 422 (:status r)))
        (is (= "CF art. 31 §2" (get-in r [:corpo :referencia])))
        (is (str/includes? (get-in r [:corpo :erro]) "2/3")))
      (is (= 422 (:status (abrir "simbolica" "maioria_qualificada_2_3")))))
    (testing "outra materia segue abrindo com qualquer quorum"
      (is (= 201 (:status (chamar ente sec :post (str "/sessoes/" sessao "/votacoes")
                                  {:objeto-tipo "proposicao" :objeto-id (str (random-uuid))
                                   :modalidade "simbolica" :quorum-tipo "maioria_simples"})))))
    (let [{:keys [status corpo]} (abrir "nominal" "maioria_qualificada_2_3")
          vid (:id corpo)]
      (is (= 201 status))
      (testing "5 sim de 9 (maioria simples, sem 2/3): o parecer prevalece"
        (doseq [[v voto] (map vector roster (concat (repeat 5 "sim") (repeat 4 "nao")))]
          (is (= 201 (:status (chamar ente sec :post (str "/sessoes/" sessao "/votacoes/" vid "/votos")
                                      {:vereador-id (str v) :voto voto})))))
        (is (= 200 (:status (chamar ente sec :post (str "/sessoes/" sessao "/votacoes/" vid "/encerramento")
                                    {:lock-version (:lock-version corpo)}))))
        (let [f (:corpo (chamar ente sec :get (str "/contas/" (:id p))))]
          (is (= "julgada" (:estado f)))
          (is (= "parecer_mantido" (:resultado f)))
          (is (= {:id vid :sim 5 :nao 4 :abstencao 0} (:votacao f)))
          (is (= "O parecer prevalece: 5 votos pela rejeição, eram precisos 6." (:frase-resultado f)))
          (is (some? (:julgada-em f)))))
      (testing "no portal, o resultado em palavras e o rotulo do PDL"
        (let [pp (first (get-in (publico :get (str "/portal/casa/" ente "/contas")) [:corpo :prestacoes]))]
          (is (= "parecer_mantido" (:resultado pp)))
          (is (= "O parecer prevalece: 5 votos pela rejeição, eram precisos 6." (:frase-resultado pp)))
          (is (= (get-in p [:proposicao :rotulo]) (:proposicao-rotulo pp))))))))

(deftest portal-so-serve-os-documentos-do-tce
  (let [ente (random-uuid)
        id (get-in (registrar! ente) [:corpo :id])
        parecer (:corpo (enviar-documento ente sec id "parecer_previo" "parecer-previo.pdf" "%PDF-1.4 parecer"))
        notif (:corpo (enviar-documento ente sec id "notificacao" "notificacao.pdf" "%PDF-1.4 notificacao"))]
    (testing "a ficha interna lista os dois; a secretaria baixa com Content-Disposition"
      (is (= #{"parecer_previo" "notificacao"} (set (map :tipo (get-in (chamar ente sec :get (str "/contas/" id))
                                                                       [:corpo :documentos])))))
      (let [r (chamar ente sec :get (str "/contas/" id "/documentos/" (:id notif)))]
        (is (= 200 (:status r)))
        (is (str/includes? (get-in r [:headers "Content-Disposition"]) "notificacao.pdf"))))
    (testing "o portal lista e serve so' o do TCE"
      (let [docs (get-in (publico :get (str "/portal/casa/" ente "/contas")) [:corpo :prestacoes 0 :documentos])]
        (is (= [{:id (:id parecer) :tipo "parecer_previo" :nome "parecer-previo.pdf"}] docs)))
      (is (= 200 (:status (publico :get (str "/portal/casa/" ente "/contas/" id "/documentos/" (:id parecer))))))
      (is (= 404 (:status (publico :get (str "/portal/casa/" ente "/contas/" id "/documentos/" (:id notif)))))))
    (testing "tipo invalido -> 400; sem papel -> 403"
      (is (= 400 (:status (enviar-documento ente sec id "foto" "x.pdf" "x"))))
      (is (= 403 (:status (enviar-documento ente ver id "outro" "x.pdf" "x")))))))

(deftest documento-com-nome-longo-e-acentuado-e-aceito
  ;; a rota lia o upload pelo multipart do Ring, que corta o cabecalho da parte em 512 BYTES (commons-fileupload2 M5) e nao
  ;; expoe o teto: um nome de 255 caracteres com acento (o maximo do Windows) voltava 400 "O envio do arquivo veio malformado."
  (let [ente (random-uuid)
        id (get-in (registrar! ente) [:corpo :id])]
    (doseq [[caso nome] [["255 caracteres acentuados (2 bytes cada)" (str (apply str (repeat 251 "ç")) ".pdf")]
                         ["255 caracteres de 3 bytes, o pior nome real" (str (apply str (repeat 251 "–")) ".pdf")]]]
      (testing caso
        (let [r (enviar-documento ente sec id "outro" nome "%PDF-1.4 anexo")]
          (is (= 201 (:status r)) (str "recusado: " (:corpo r)))
          (is (= (arquivo/nome-de-arquivo nome) (get-in r [:corpo :nome])) "o nome limpo da borda comum, com a extensao"))))
    (testing "caractere de formato invisivel (U+202E inverte a direcao do nome na tela) sai do nome"
      (is (= "recibofdp.pdf" (get-in (enviar-documento ente sec id "outro" "recibo\u202Efdp.pdf" "%PDF-1.4 x") [:corpo :nome]))))))

(deftest documento-acima-do-teto-e-tipo-declarado-longo
  (let [ente (random-uuid)
        id (get-in (registrar! ente) [:corpo :id])]
    (testing "arquivo acima de 10 MB: 413 com o texto das contas (a tela fala em documento, nao em anexo)"
      (let [r (enviar-documento ente sec id "outro" "grande.pdf" (apply str (repeat (inc (* 10 1024 1024)) "a")))]
        (is (= 413 (:status r)))
        (is (= "O documento passa de 10 MB." (get-in r [:corpo :erro])))))
    (testing "tipo declarado valido na forma, mas acima dos 200 da coluna: guardado como octet-stream, nunca 500"
      (let [r (enviar-documento ente sec id "outro" "x.pdf" "%PDF-1.4 x"
                                :tipo-midia (str "application/" (apply str (repeat 120 "a"))))
            longo (enviar-documento ente sec id "outro" "y.pdf" "%PDF-1.4 y"
                                    :tipo-midia (str (apply str (repeat 110 "a")) "/" (apply str (repeat 110 "b"))))]
        (is (= 201 (:status r)) "132 caracteres cabem")
        (is (= 201 (:status longo)) (str "recusado: " (:corpo longo)))
        (is (= "application/octet-stream"
               (get-in (chamar ente sec :get (str "/contas/" id "/documentos/" (get-in longo [:corpo :id])))
                       [:headers "Content-Type"])))))))

(deftest parametros-so-o-admin-ente-muda
  (let [ente (random-uuid)]
    (is (= {:prazo-defesa-dias 15 :prazo-julgamento-dias 60 :padrao true}
           (:corpo (chamar ente sec :get "/parametros-de-contas"))))
    (is (= 403 (:status (chamar ente sec :put "/parametros-de-contas" {:prazo-defesa-dias 20 :prazo-julgamento-dias 90}))))
    (is (= 400 (:status (chamar ente adm :put "/parametros-de-contas" {:prazo-defesa-dias 0 :prazo-julgamento-dias 90}))))
    (is (= {:prazo-defesa-dias 20 :prazo-julgamento-dias 90 :padrao false}
           (:corpo (chamar ente adm :put "/parametros-de-contas" {:prazo-defesa-dias 20 :prazo-julgamento-dias 90}))))
    (testing "o prazo de julgamento do registro seguinte usa o parametro novo"
      (is (= "2026-10-30" (get-in (registrar! ente) [:corpo :prazo-julgamento-ate]))))
    (is (= 403 (:status (chamar ente ver :get "/parametros-de-contas"))))))
