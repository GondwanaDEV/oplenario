(ns oplenario.participacao.prorrogacao-do-cidadao-test
  "INTEGRACAO (PG real) — a prorrogacao que o REQUERENTE le no proprio protocolo (LAI art. 11 §2º: 'justificativa
  expressa, da qual sera cientificado o requerente'). Antes, a secretaria prorrogava com justificativa e ela ficava so'
  em `participacao.prorrogacao`; a cidada via a data nova e nada mais. Prova, contra o banco real e pela borda HTTP:
  (1) o dono ve `prorrogacao` completa (e-SIC e ouvidoria) e nil no nao prorrogado; (2) nada alem das 4 chaves — nunca
  quem prorrogou; (3) outro cidadao e outra Casa nao recebem o protocolo alheio nem a justificativa; (4) a manifestacao
  ANONIMA prorrogada nao aparece nem para a autora; (5) o acompanhamento PUBLICO por protocolo (sequencial, adivinhavel)
  NAO carrega a justificativa — com controle positivo: o MESMO texto aparece no corpo do dono e nao no da rota publica."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.migracao :as migracao]
            [oplenario.participacao.adapters.out.meus-protocolos :as out-meus]
            [oplenario.participacao.components.repositorio :as repo-part]
            [oplenario.participacao.controllers :as controllers]
            [oplenario.participacao.diplomat.http.in :as participacao-http])
  (:import (java.time Instant)))

(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*repo* (repo-part/->RepoParticipacaoPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

;; relogio do protocolo: 03/07/2026 (Fortaleza). e-SIC vence 23/07 (+20); ouvidoria vence 02/08 (+30).
(def ^:private relogio (tempo/relogio-fixo (Instant/parse "2026-07-03T12:00:00Z")))
;; relogio do ato de prorrogar: 08/07/2026 15:30Z
(def ^:private relogio-da-prorrogacao (tempo/relogio-fixo (Instant/parse "2026-07-08T15:30:00Z")))

(defn- cidadao [ente iid] {:ente-id ente :identidade-id iid :papeis #{}})
(defn- servidor [ente iid] {:ente-id ente :identidade-id iid :papeis #{"secretario"}})

(def ^:private justificativa-esic
  "Busca no arquivo morto: o acervo de 2019 ainda nao foi digitalizado. SEGREDO-JUSTIFICATIVA-ESIC")
(def ^:private justificativa-ouvidoria
  "Consulta a secretaria de obras sobre a vistoria. SEGREDO-JUSTIFICATIVA-OUVIDORIA")

(defn- meus-protocolos-wire [ente iid]
  (out-meus/meus-protocolos->wire (controllers/meus-protocolos *repo* (cidadao ente iid) relogio)))

;; ---------------------------------------------------------------- o dono ve a prorrogacao

(deftest o-requerente-ve-a-prorrogacao-do-e-sic-e-nil-no-nao-prorrogado
  (let [ente (random-uuid) eu (random-uuid) sec (servidor ente (random-uuid))
        prorrogado (controllers/protocolar-pedido *repo* relogio (cidadao ente eu) {:assunto "Contratos" :descricao "Lista."})
        intacto    (controllers/protocolar-pedido *repo* relogio (cidadao ente eu) {:assunto "Diarias" :descricao "Valores."})]
    (controllers/prorrogar-pedido! *repo* relogio-da-prorrogacao sec (:id prorrogado) {:justificativa justificativa-esic})
    (let [itens (:pedidos-esic (meus-protocolos-wire ente eu))
          p (first (filter #(= (:protocolo prorrogado) (:protocolo %)) itens))
          i (first (filter #(= (:protocolo intacto) (:protocolo %)) itens))]
      (is (= {:de-data "2026-07-23" :para-data "2026-08-02" :justificativa justificativa-esic
              :prorrogado-em "2026-07-08T15:30:00Z"}
             (:prorrogacao p))
          "a data ORIGINAL, a NOVA, a justificativa e o instante em que foi prorrogado")
      (is (= "2026-08-02" (:vence-em p)) "o vencimento efetivo ja' mostrava a data nova: agora a cidada sabe POR QUE")
      (is (contains? i :prorrogacao))
      (is (nil? (:prorrogacao i)) "o nao prorrogado: nil por chave"))))

(deftest o-manifestante-identificado-ve-a-prorrogacao-da-ouvidoria
  (let [ente (random-uuid) eu (random-uuid) sec (servidor ente (random-uuid))
        m (controllers/protocolar-manifestacao! *repo* relogio (cidadao ente eu)
                                                {:tipo "reclamacao" :assunto "Fila" :descricao "Demora." :anonima false})
        n (controllers/protocolar-manifestacao! *repo* relogio (cidadao ente eu)
                                                {:tipo "sugestao" :assunto "Wi-fi" :descricao "No plenario." :anonima false})]
    (controllers/prorrogar-manifestacao! *repo* relogio-da-prorrogacao sec (:id m) {:justificativa justificativa-ouvidoria})
    (let [itens (:manifestacoes (meus-protocolos-wire ente eu))
          p (first (filter #(= (:protocolo m) (:protocolo %)) itens))
          q (first (filter #(= (:protocolo n) (:protocolo %)) itens))]
      (is (= {:de-data "2026-08-02" :para-data "2026-09-01" :justificativa justificativa-ouvidoria
              :prorrogado-em "2026-07-08T15:30:00Z"}
             (:prorrogacao p))
          "30 dias por igual periodo (Lei 13.460), somados a partir do vencimento ORIGINAL")
      (is (nil? (:prorrogacao q))))))

(deftest a-prorrogacao-nao-vaza-quem-prorrogou-nem-o-resto
  (let [ente (random-uuid) eu (random-uuid) sec-id (random-uuid) sec (servidor ente sec-id)
        p (controllers/protocolar-pedido *repo* relogio (cidadao ente eu) {:assunto "Contratos" :descricao "Lista."})
        s (controllers/solicitar-titular! *repo* relogio (cidadao ente eu) {:tipo "acessar" :detalhe nil})]
    (controllers/prorrogar-pedido! *repo* relogio-da-prorrogacao sec (:id p) {:justificativa justificativa-esic})
    (let [w (meus-protocolos-wire ente eu)
          texto (json/write-value-as-string w)]
      (is (= #{:de-data :para-data :justificativa :prorrogado-em} (set (keys (get-in w [:pedidos-esic 0 :prorrogacao])))))
      (is (not (str/includes? texto (str sec-id))) "quem prorrogou (servidor) nunca sai")
      (is (not (str/includes? texto (str ente))) "nem o tenant")
      (is (= (:protocolo s) (get-in w [:solicitacoes-lgpd 0 :protocolo])))
      (is (not (contains? (get-in w [:solicitacoes-lgpd 0]) :prorrogacao)) "a LGPD nao tem prorrogacao"))))

;; ---------------------------------------------------------------- quem nao e' o dono

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "cidadao"} :papeis papeis})))

(defn- service-fn []
  (let [auth (it/autenticacao (idp-dev/idp-dev) (fake-repo-identidade #{}))]
    (-> (http/servico (config/carregar)
                      (participacao-http/rotas {:auth auth :repo-participacao *repo* :relogio relogio
                                                :resolver-ente-publico participacao-http/resolver-ente-publico-uuid})
                      it/globais)
        ph/create-server ::ph/service-fn)))

(defn- como [ente iid]
  {"authorization" (str "Bearer " (json/write-value-as-string {:sub "u" :ente-id (str ente) :identidade-id (str iid)}))})

(defn- ler [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(deftest outro-cidadao-e-outra-casa-nao-recebem-o-protocolo-alheio-nem-a-justificativa
  (let [ente (random-uuid) dona (random-uuid) intruso (random-uuid) sec (servidor ente (random-uuid))
        svc (service-fn)
        p (controllers/protocolar-pedido *repo* relogio (cidadao ente dona) {:assunto "Contratos" :descricao "Lista."})
        m (controllers/protocolar-manifestacao! *repo* relogio (cidadao ente dona)
                                                {:tipo "reclamacao" :assunto "Fila" :descricao "Demora." :anonima false})]
    (controllers/prorrogar-pedido! *repo* relogio-da-prorrogacao sec (:id p) {:justificativa justificativa-esic})
    (controllers/prorrogar-manifestacao! *repo* relogio-da-prorrogacao sec (:id m) {:justificativa justificativa-ouvidoria})
    (testing "controle positivo: a DONA recebe as duas justificativas, pela borda HTTP"
      (let [r (pt/response-for svc :get "/portal/meus-protocolos" :headers (como ente dona))]
        (is (= 200 (:status r)))
        (is (str/includes? (:body r) "SEGREDO-JUSTIFICATIVA-ESIC"))
        (is (str/includes? (:body r) "SEGREDO-JUSTIFICATIVA-OUVIDORIA"))
        (is (= justificativa-esic (get-in (ler r) [:pedidos-esic 0 :prorrogacao :justificativa])))))
    (testing "outro cidadao da MESMA Casa: listas vazias, nenhum rastro do texto"
      (let [r (pt/response-for svc :get "/portal/meus-protocolos" :headers (como ente intruso))]
        (is (= 200 (:status r)))
        (is (= {:pedidos-esic [] :solicitacoes-lgpd [] :manifestacoes []} (ler r)))
        (is (not (str/includes? (:body r) "SEGREDO-JUSTIFICATIVA")))))
    (testing "a dona numa OUTRA Casa (RLS): nada"
      (let [r (pt/response-for svc :get "/portal/meus-protocolos" :headers (como (random-uuid) dona))]
        (is (= {:pedidos-esic [] :solicitacoes-lgpd [] :manifestacoes []} (ler r)))
        (is (not (str/includes? (:body r) "SEGREDO-JUSTIFICATIVA")))))
    (testing "o detalhe do pedido por id segue so' do dono (403 para o intruso) e sem justificativa"
      (is (= 403 (:status (pt/response-for svc :get (str "/portal/esic/pedidos/" (:id p)) :headers (como ente intruso)))))
      (is (not (str/includes? (:body (pt/response-for svc :get (str "/portal/esic/pedidos/" (:id p))
                                                      :headers (como ente dona)))
                              "SEGREDO-JUSTIFICATIVA"))
          "o detalhe do dono nao ganhou a justificativa: ela vive so' em meus-protocolos"))))

(deftest a-manifestacao-anonima-prorrogada-nao-aparece-nem-para-a-autora
  (let [ente (random-uuid) autora (random-uuid) sec (servidor ente (random-uuid)) svc (service-fn)
        anon (controllers/protocolar-manifestacao! *repo* relogio (cidadao ente autora)
                                                   {:tipo "denuncia" :assunto "Obra" :descricao "Parada." :anonima true})]
    (controllers/prorrogar-manifestacao! *repo* relogio-da-prorrogacao sec (:id anon) {:justificativa justificativa-ouvidoria})
    (let [r (pt/response-for svc :get "/portal/meus-protocolos" :headers (como ente autora))]
      (is (= [] (:manifestacoes (ler r))) "nao ha dono persistido: a anonima nunca esta na lista da autora")
      (is (not (str/includes? (:body r) "SEGREDO-JUSTIFICATIVA"))))))

;; ---------------------------------------------------------------- o acompanhamento PUBLICO nao carrega a justificativa

(deftest o-acompanhamento-publico-nao-carrega-a-justificativa
  ;; O protocolo e' SEQUENCIAL (ESIC-2026-000001, OUV-2026-000001...): qualquer pessoa o adivinha, e a justificativa e'
  ;; texto livre que pode citar fato, setor e pessoa. A rota publica fica no minimo: protocolo, estado e dias.
  (let [ente (random-uuid) eu (random-uuid) sec (servidor ente (random-uuid)) svc (service-fn)
        p (controllers/protocolar-pedido *repo* relogio (cidadao ente eu) {:assunto "Contratos" :descricao "Lista."})
        m (controllers/protocolar-manifestacao! *repo* relogio (cidadao ente eu)
                                                {:tipo "reclamacao" :assunto "Fila" :descricao "Demora." :anonima false})
        anon (controllers/protocolar-manifestacao! *repo* relogio (cidadao ente eu)
                                                   {:tipo "denuncia" :assunto "Obra" :descricao "Parada." :anonima true})]
    (controllers/prorrogar-pedido! *repo* relogio-da-prorrogacao sec (:id p) {:justificativa justificativa-esic})
    (controllers/prorrogar-manifestacao! *repo* relogio-da-prorrogacao sec (:id m) {:justificativa justificativa-ouvidoria})
    (controllers/prorrogar-manifestacao! *repo* relogio-da-prorrogacao sec (:id anon) {:justificativa justificativa-ouvidoria})
    (testing "controle positivo: o detector acha o MESMO texto no corpo do dono (senao o teste seria cego)"
      (is (str/includes? (:body (pt/response-for svc :get "/portal/meus-protocolos" :headers (como ente eu)))
                         "SEGREDO-JUSTIFICATIVA-ESIC")))
    (doseq [[rotulo caminho chaves]
            [["ouvidoria identificada" (str "/portal/casa/" ente "/ouvidoria/acompanhar/" (:protocolo m))
              #{:protocolo :estado :dias-restantes}]
             ["ouvidoria anonima" (str "/portal/casa/" ente "/ouvidoria/acompanhar/" (:protocolo anon))
              #{:protocolo :estado :dias-restantes}]
             ["e-SIC" (str "/portal/casa/" ente "/esic/acompanhar/" (:protocolo p))
              #{:protocolo :estado :dias-restantes}]]]
      (testing rotulo
        (let [r (pt/response-for svc :get caminho)]
          (is (= 200 (:status r)))
          (is (= chaves (set (keys (ler r)))) "a rota publica segue no minimo, sem chave nova")
          (is (not (str/includes? (:body r) "SEGREDO-JUSTIFICATIVA")) "a justificativa nao esta no corpo")
          (is (not (re-find #"(?i)justific|prorroga|de-data|para-data" (:body r))) "nem o rastro de que existe uma"))))))
