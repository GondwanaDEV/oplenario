(ns oplenario.participacao.complemento-test
  "INTEGRACAO (PG real + borda HTTP) — o COMPLEMENTO DA RESPOSTA (ADR-0022): a secretaria acrescenta um texto a um protocolo
  que a Casa ja' respondeu, mesmo depois da janela de 10 minutos dos anexos. Prova: o ato feliz nas tres especies (imutavel,
  com autor e instante, sem mexer em estado nem em prazo); QUANDO cabe (409 em palavras com o protocolo aberto, manifestacao
  so' arquivada, e o 200 no indeferido e no recurso decidido); as recusas (400, 403, 404 de outra Casa); a janela de anexos
  que REABRE por 10 minutos sem mudar o limite de 5 nem a janela do requerente; onde aparece (balcao, e o dono em
  /meus-protocolos, em ordem, sem a identidade de quem escreveu; nunca nas rotas publicas); e o banco (RLS, append-only,
  CHECKs)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.components.objeto-store :as store]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.participacao.components.repositorio :as repo-part]
            [oplenario.participacao.controllers :as controllers]
            [oplenario.participacao.diplomat.http.in :as participacao-http])
  (:import (java.io ByteArrayInputStream)
           (java.time Instant)))

(def ^:dynamic *c* nil)
(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*c* c *repo* (repo-part/->RepoParticipacaoPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

;; ---------------------------------------------------------------- o cenario: Casa, relogio e object store em memoria

(defn- store-memoria []
  (let [m (atom {})]
    #_{:clj-kondo/ignore [:missing-protocol-method]}
    {:m m
     :store (reify store/ObjetoStore
              (guardar! [_ k b _] (swap! m assoc k b) k)
              (obter [_ k] (get @m k))
              (abrir [_ k] (some-> (get @m k) ByteArrayInputStream.))
              (remover! [_ k] (swap! m dissoc k) nil)
              (listar [_ _ _] []))}))

(def ^:private t0 (Instant/parse "2026-07-03T12:00:00Z"))

(defn- cenario []
  (let [instante (atom t0)
        {:keys [m store]} (store-memoria)]
    {:ente (random-uuid) :instante instante :m m :store store
     :relogio (reify tempo/Relogio (agora [_] @instante))}))

(defn- avancar! [c segundos] (swap! (:instante c) #(.plusSeconds ^Instant % (long segundos))))

(defn- cidadao [ente iid] {:ente-id ente :identidade-id iid :papeis #{}})
(defn- servidor [ente iid] {:ente-id ente :identidade-id iid :papeis #{"secretario"}})

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- servico
  "O servico da borda para quem tem `papeis` (o fake devolve os mesmos papeis a qualquer identidade). `pessoas` (opcional) =
  o seam do host que nomeia quem agiu pela Casa."
  ([c papeis] (servico c papeis nil))
  ([c papeis pessoas]
   (let [auth (it/autenticacao (idp-dev/idp-dev) (fake-repo-identidade papeis))]
     (-> (http/servico (config/carregar)
                       (participacao-http/rotas (cond-> {:auth auth :repo-participacao *repo* :relogio (:relogio c)
                                                         :objeto-store (:store c)
                                                         :resolver-ente-publico participacao-http/resolver-ente-publico-uuid}
                                                  pessoas (assoc :pessoas pessoas)))
                       it/globais)
         ph/create-server ::ph/service-fn))))

(defn- como [ente iid]
  {"authorization" (str "Bearer " (json/write-value-as-string {:sub "u" :ente-id (str ente) :identidade-id (str iid)}))})

(defn- json-como [ente iid]
  (assoc (como ente iid) "Content-Type" "application/json"))

(defn- ler [r] (when (seq (:body r)) (json/read-value (:body r) json/keyword-keys-object-mapper)))

(defn- complementar
  "POST /atendimento/<rota>/:id/complementos com o corpo JSON `corpo` (mapa ou string crua)."
  [svc ente quem rota id corpo]
  (let [r (pt/response-for svc :post (str "/atendimento/" rota "/" id "/complementos")
                           :headers (json-como ente quem)
                           :body (if (string? corpo) corpo (json/write-value-as-string corpo)))]
    {:status (:status r) :corpo (ler r) :bruto (:body r)}))

(defn- detalhe [svc ente quem rota id]
  (ler (pt/response-for svc :get (str "/atendimento/" rota "/" id) :headers (como ente quem))))

(def ^:private pdf "%PDF-1.4 planilha da folha de pagamento de 2025")

(defn- multipart [nome tipo conteudo]
  (let [fronteira "----oplenario-teste-complemento"]
    {:ct (str "multipart/form-data; boundary=" fronteira)
     :body (str "--" fronteira "\r\n"
                "Content-Disposition: form-data; name=\"arquivo\"; filename=\"" nome "\"\r\n"
                "Content-Type: " tipo "\r\n\r\n" conteudo "\r\n"
                "--" fronteira "--\r\n")}))

(defn- enviar [svc caminho headers nome tipo conteudo]
  (let [{:keys [ct body]} (multipart nome tipo conteudo)
        r (pt/response-for svc :post caminho :headers (assoc headers "Content-Type" ct) :body body)]
    {:status (:status r) :corpo (ler r)}))

(defn- anexar-da-casa
  "O conteudo muda com o nome: o MESMO arquivo reenviado e' idempotente (devolve o anexo, sem gastar vaga)."
  [svc ente quem rota id nome]
  (enviar svc (str "/atendimento/" rota "/" id "/anexos") (como ente quem) nome "application/pdf" (str pdf " " nome)))

(defn- anexar-meu [svc ente quem rota id nome]
  (enviar svc (str "/portal/meus-protocolos/" rota "/" id "/anexos") (como ente quem) nome "application/pdf" pdf))

(defn- linhas-do-banco [ente sql & params]
  (tenancy/com-tenant* (:ds *c*) ente #(jdbc/execute! % (into [sql] params) {:builder-fn rs/as-unqualified-maps})))

;; as tres especies: como protocolar e como responder (cada uma pelo seu controller)
(def ^:private especies
  {:esic {:rota "esic" :tipo-banco "pedido_esic" :chave-lista :pedidos-esic
          :protocolar (fn [c eu] (:id (controllers/protocolar-pedido *repo* (:relogio c) (cidadao (:ente c) eu)
                                                                     {:assunto "Folha" :descricao "Quero a folha."})))
          :responder! (fn [c sec id] (controllers/responder-pedido! *repo* (:relogio c) (servidor (:ente c) sec) id {:corpo "Segue."}))}
   :ouvidoria {:rota "ouvidoria" :tipo-banco "manifestacao_ouvidoria" :chave-lista :manifestacoes
               :protocolar (fn [c eu] (:id (controllers/protocolar-manifestacao! *repo* (:relogio c) (cidadao (:ente c) eu)
                                                                                 {:tipo "reclamacao" :assunto "Fila" :descricao "Demora." :anonima false})))
               :responder! (fn [c sec id] (controllers/responder-manifestacao! *repo* (:relogio c) (servidor (:ente c) sec) id {:corpo "Segue."}))}
   :lgpd {:rota "lgpd" :tipo-banco "solicitacao_titular" :chave-lista :solicitacoes-lgpd
          :protocolar (fn [c eu] (:id (controllers/solicitar-titular! *repo* (:relogio c) (cidadao (:ente c) eu)
                                                                      {:tipo "acessar" :detalhe "Meus dados."})))
          :responder! (fn [c sec id] (controllers/responder-solicitacao! *repo* (:relogio c) (servidor (:ente c) sec) id {:corpo "Segue."}))}})

(defn- protocolo-respondido! [c especie eu sec]
  (let [{:keys [protocolar responder!]} (especies especie)
        id (protocolar c eu)]
    (responder! c sec id)
    id))

;; ---------------------------------------------------------------- o ato feliz

(deftest complemento-feliz-nas-tres-especies
  (doseq [especie [:esic :ouvidoria :lgpd]]
    (testing (name especie)
      (let [c (cenario) ente (:ente c) eu (random-uuid) sec (random-uuid)
            {:keys [rota tipo-banco]} (especies especie)
            id (protocolo-respondido! c especie eu sec)
            svc (servico c #{"secretario"})
            antes (detalhe svc ente sec rota id)
            prazo-antes (linhas-do-banco ente "SELECT estado, cumprida_em FROM participacao.prazo_ativo WHERE objeto_id = ?" id)
            _ (avancar! c 7200)
            r (complementar svc ente sec rota id {:corpo "Segue também o anexo II, que faltou."})]
        (is (= 201 (:status r)))
        (testing "o 201 diz so' o que a tela precisa: id, texto, instante — nunca quem escreveu"
          (is (= #{:id :corpo :complementado-em} (set (keys (:corpo r)))))
          (is (= "Segue também o anexo II, que faltou." (get-in r [:corpo :corpo])))
          (is (= "2026-07-03T14:00:00Z" (get-in r [:corpo :complementado-em])))
          (is (not (str/includes? (:bruto r) (str sec)))))
        (testing "a linha: o protocolo, o texto, o instante do relogio e o autor vindo do ATOR"
          (let [[l & mais] (linhas-do-banco ente "SELECT * FROM participacao.complemento WHERE objeto_id = ?" id)]
            (is (nil? mais))
            (is (= [tipo-banco id sec "Segue também o anexo II, que faltou."]
                   [(:objeto_tipo l) (:objeto_id l) (:complementado_por l) (:corpo l)]))))
        (testing "NAO mexe em estado nem em prazo: o prazo ja' foi cumprido pela resposta"
          (let [depois (detalhe svc ente sec rota id)]
            (is (= (:estado antes) (:estado depois)))
            (is (= (select-keys antes [:aberto :prazo-vigente :dias-restantes :prorrogado])
                   (select-keys depois [:aberto :prazo-vigente :dias-restantes :prorrogado]))))
          (is (= prazo-antes (linhas-do-banco ente "SELECT estado, cumprida_em FROM participacao.prazo_ativo WHERE objeto_id = ?" id))))))))

(deftest cada-complemento-e-um-ato-sem-limite-numerico
  (let [c (cenario) ente (:ente c) sec (random-uuid) svc (servico c #{"secretario"})
        id (protocolo-respondido! c :esic (random-uuid) sec)]
    (dotimes [i 7]
      (avancar! c 60)
      (is (= 201 (:status (complementar svc ente sec "esic" id {:corpo (str "complemento " i)}))) (str i)))
    (is (= 7 (count (linhas-do-banco ente "SELECT id FROM participacao.complemento WHERE objeto_id = ?" id))))
    (testing "o historico do balcao os traz na ordem em que chegaram, junto da resposta"
      (let [h (:historico (detalhe svc ente sec "esic" id))]
        (is (= (into ["resposta"] (repeat 7 "complemento")) (map :tipo h)))
        (is (= (map #(str "complemento " %) (range 7)) (map :texto (rest h))))))))

;; ---------------------------------------------------------------- quando cabe

(deftest protocolo-ainda-aberto-409-em-palavras
  (doseq [especie [:esic :ouvidoria :lgpd]]
    (let [c (cenario) ente (:ente c) sec (random-uuid) svc (servico c #{"secretario"})
          {:keys [rota protocolar]} (especies especie)
          id (protocolar c (random-uuid))
          r (complementar svc ente sec rota id {:corpo "Algo a mais."})]
      (is (= 409 (:status r)) (name especie))
      (is (= "Responda o pedido antes de complementar." (get-in r [:corpo :erro])))
      (is (false? (get-in (detalhe svc ente sec rota id) [:acoes :pode-complementar])) "a tela nao oferece")
      (is (empty? (linhas-do-banco ente "SELECT id FROM participacao.complemento WHERE objeto_id = ?" id))))))

(deftest ouvidoria-so-arquivada-nao-aceita
  (let [c (cenario) ente (:ente c) sec (random-uuid) eu (random-uuid) svc (servico c #{"secretario"})
        m (controllers/protocolar-manifestacao! *repo* (:relogio c) (cidadao ente eu)
                                                {:tipo "denuncia" :assunto "Obra" :descricao "Parada." :anonima false})]
    (controllers/arquivar-manifestacao! *repo* (:relogio c) (servidor ente sec) (:id m) {:motivo "Fora da competencia."})
    (let [r (complementar svc ente sec "ouvidoria" (:id m) {:corpo "Algo a mais."})]
      (is (= 409 (:status r)) "arquivar e' encerrar SEM resposta de merito: nao ha resposta a complementar")
      (is (= "Responda o pedido antes de complementar." (get-in r [:corpo :erro])))
      (is (false? (get-in (detalhe svc ente sec "ouvidoria" (:id m)) [:acoes :pode-complementar]))))))

(deftest esic-indeferido-e-recurso-decidido-cabem
  (let [c (cenario) ente (:ente c) sec (random-uuid) eu (random-uuid) svc (servico c #{"secretario"})
        ind (:id (controllers/protocolar-pedido *repo* (:relogio c) (cidadao ente eu) {:assunto "A" :descricao "A"}))
        _ (controllers/indeferir-pedido! *repo* (:relogio c) (servidor ente sec) ind {:fundamentacao "Informação sigilosa."})
        ped (protocolo-respondido! c :esic eu sec)
        rec (controllers/interpor-recurso! *repo* (:relogio c) (cidadao ente eu) ped {:motivo "Faltou o valor."})]
    (is (true? (get-in (detalhe svc ente sec "esic" ind) [:acoes :pode-complementar])))
    (is (= 201 (:status (complementar svc ente sec "esic" ind {:corpo "Indicamos o recurso cabível."}))) "indeferido")
    (testing "o recurso ainda pendente nao tira o direito: o pedido ja' foi respondido"
      (is (= 201 (:status (complementar svc ente sec "esic" ped {:corpo "Anexo ao recurso."})))))
    (testing "o recurso decidido tambem; e o complemento nao reabre recurso nem decide o recurso"
      (controllers/decidir-recurso! *repo* (:relogio c) (servidor ente sec) (:id rec) {:corpo "Provido."})
      (let [antes (detalhe svc ente sec "esic" ped)]
        (is (= 201 (:status (complementar svc ente sec "esic" ped {:corpo "Segue o documento do recurso."}))))
        (is (= (select-keys antes [:estado :recurso]) (select-keys (detalhe svc ente sec "esic" ped) [:estado :recurso])))))))

;; ---------------------------------------------------------------- as recusas

(deftest corpo-invalido-400
  (let [c (cenario) ente (:ente c) sec (random-uuid) svc (servico c #{"secretario"})
        id (protocolo-respondido! c :esic (random-uuid) sec)]
    (doseq [[rotulo corpo] [["sem corpo" {}]
                            ["vazio" {:corpo ""}]
                            ["so' espacos" {:corpo "   \n "}]
                            ["nulo" {:corpo nil}]
                            ["tipo errado" {:corpo 12}]
                            ["acima de 50000" {:corpo (apply str (repeat 50001 "a"))}]
                            ["nao e' objeto" "[1]"]]]
      (is (= 400 (:status (complementar svc ente sec "esic" id corpo))) rotulo))
    (is (= 201 (:status (complementar svc ente sec "esic" id {:corpo (apply str (repeat 50000 "a"))}))) "no teto passa")
    (testing "campo forjado e' descartado: o autor e' sempre o ator"
      (let [forjado (random-uuid)]
        (is (= 201 (:status (complementar svc ente sec "esic" id {:corpo "Ok." :complementado-por (str forjado)
                                                                  :complementado-em "2001-01-01T00:00:00Z"}))))
        (is (= [sec] (map :complementado_por (linhas-do-banco ente "SELECT complementado_por FROM participacao.complemento WHERE corpo = 'Ok.'"))))))
    (is (= 2 (count (linhas-do-banco ente "SELECT id FROM participacao.complemento WHERE objeto_id = ?" id))))))

(deftest so-a-secretaria-complementa
  (let [c (cenario) ente (:ente c) eu (random-uuid) sec (random-uuid)
        id (protocolo-respondido! c :esic eu sec)]
    (doseq [papeis [#{} #{"vereador"}]]
      (is (= 403 (:status (complementar (servico c papeis) ente eu "esic" id {:corpo "Quero complementar."}))) (str papeis)))
    (is (= 401 (:status (pt/response-for (servico c #{"secretario"}) :post (str "/atendimento/esic/" id "/complementos")
                                         :headers {"Content-Type" "application/json"} :body "{\"corpo\":\"x\"}"))))
    (is (empty? (linhas-do-banco ente "SELECT id FROM participacao.complemento WHERE objeto_id = ?" id)))))

(deftest outra-casa-e-inexistente-404
  (let [c (cenario) ente (:ente c) sec (random-uuid) svc (servico c #{"secretario"})
        id (protocolo-respondido! c :esic (random-uuid) sec)]
    (is (= 404 (:status (complementar svc (random-uuid) sec "esic" id {:corpo "Outra Casa."}))))
    (is (= 404 (:status (complementar svc ente sec "esic" (random-uuid) {:corpo "Nao existe."}))))
    (is (= 400 (:status (complementar svc ente sec "esic" "nao-e-uuid" {:corpo "x"}))) "id que nao e' uuid: 400, nunca 500")
    (is (empty? (linhas-do-banco ente "SELECT id FROM participacao.complemento WHERE objeto_id = ?" id)))))

;; ---------------------------------------------------------------- a janela de anexos reabre

(deftest o-complemento-reabre-a-janela-de-anexos-da-casa-por-10-minutos
  (let [c (cenario) ente (:ente c) sec (random-uuid) eu (random-uuid) svc (servico c #{"secretario"})
        id (protocolo-respondido! c :esic eu sec)]
    (avancar! c 3600)
    (testing "uma hora depois da resposta: a janela fechou, o balcao nao oferece anexar mas oferece complementar"
      (is (= 409 (:status (anexar-da-casa svc ente sec "esic" id "tarde.pdf"))))
      (let [a (:acoes (detalhe svc ente sec "esic" id))]
        (is (false? (:pode-anexar a)))
        (is (true? (:pode-complementar a)))))
    (is (= 201 (:status (complementar svc ente sec "esic" id {:corpo "Segue o documento."}))))
    (testing "depois do complemento: aceita anexar, e a tela volta a oferecer"
      (is (true? (get-in (detalhe svc ente sec "esic" id) [:acoes :pode-anexar])))
      (is (= 201 (:status (anexar-da-casa svc ente sec "esic" id "folha.pdf")))))
    (testing "o minuto 10 vale; passado, fecha de novo (e dá para complementar outra vez, reabrindo)"
      (avancar! c 600)
      (is (= 201 (:status (anexar-da-casa svc ente sec "esic" id "no-limite.pdf"))))
      (avancar! c 1)
      (is (= 409 (:status (anexar-da-casa svc ente sec "esic" id "tarde-de-novo.pdf"))))
      (is (= 201 (:status (complementar svc ente sec "esic" id {:corpo "Mais um."}))))
      (is (= 201 (:status (anexar-da-casa svc ente sec "esic" id "reaberta.pdf")))))))

(deftest o-limite-de-5-anexos-da-casa-nao-muda
  (let [c (cenario) ente (:ente c) sec (random-uuid) svc (servico c #{"secretario"})
        id (protocolo-respondido! c :lgpd (random-uuid) sec)]
    (dotimes [i 5] (is (= 201 (:status (anexar-da-casa svc ente sec "lgpd" id (str "a" i ".pdf"))))))
    (avancar! c 3600)
    (is (= 201 (:status (complementar svc ente sec "lgpd" id {:corpo "Complemento."}))))
    (let [r (anexar-da-casa svc ente sec "lgpd" id "sexto.pdf")]
      (is (= 409 (:status r)) "janela reaberta, mas o limite de 5 vigentes segue valendo")
      (is (re-find #"5 anexos" (get-in r [:corpo :erro]))))
    (is (false? (get-in (detalhe svc ente sec "lgpd" id) [:acoes :pode-anexar])))
    (is (= 5 (count (linhas-do-banco ente "SELECT id FROM participacao.anexo WHERE objeto_id = ?" id))))))

(deftest o-complemento-nao-reabre-a-janela-do-requerente
  (let [c (cenario) ente (:ente c) sec (random-uuid) eu (random-uuid) svc (servico c #{"secretario"})
        cid (servico c #{})
        id (protocolo-respondido! c :esic eu sec)]
    (avancar! c 3600)
    (is (= 201 (:status (complementar svc ente sec "esic" id {:corpo "Complemento."}))))
    (is (= 409 (:status (anexar-meu cid ente eu "esic" id "meu.pdf")))
        "a janela do requerente e' a do protocolo (10 minutos depois de protocolar): o complemento da Casa nao a reabre")))

;; ---------------------------------------------------------------- onde aparece

(deftest o-balcao-mostra-o-complemento-com-o-nome-de-quem-escreveu
  (let [c (cenario) ente (:ente c) sec (random-uuid) svc (servico c #{"secretario"} (fn [ids] (into {} (map (fn [i] [i {:nome "Maria Silva" :cpf-mascarado "***.111.222-**"}])) ids)))
        id (protocolo-respondido! c :esic (random-uuid) sec)]
    (avancar! c 7200)
    (complementar svc ente sec "esic" id {:corpo "Segue o anexo II."})
    (let [[resposta complemento :as h] (:historico (detalhe svc ente sec "esic" id))]
      (is (= 2 (count h)))
      (is (= "resposta" (:tipo resposta)))
      (is (= {:tipo "complemento" :texto "Segue o anexo II." :por "Maria Silva" :em "2026-07-03T14:00:00Z"} complemento)))))

(deftest o-dono-ve-o-complemento-em-meus-protocolos-sem-a-identidade-de-quem-escreveu
  (doseq [especie [:esic :ouvidoria :lgpd]]
    (testing (name especie)
      (let [c (cenario) ente (:ente c) eu (random-uuid) intruso (random-uuid) sec (random-uuid)
            {:keys [rota chave-lista]} (especies especie)
            svc (servico c #{"secretario"}) cid (servico c #{})
            id (protocolo-respondido! c especie eu sec)]
        (avancar! c 3600)
        (complementar svc ente sec rota id {:corpo "Primeiro complemento."})
        (avancar! c 60)
        (complementar svc ente sec rota id {:corpo "Segundo complemento."})
        (let [r (pt/response-for cid :get "/portal/meus-protocolos" :headers (como ente eu))
              item (first (filter #(= (str id) (:id %)) (get (ler r) chave-lista)))]
          (is (= 200 (:status r)))
          (is (= [{:corpo "Primeiro complemento." :complementado-em "2026-07-03T13:00:00Z"}
                  {:corpo "Segundo complemento." :complementado-em "2026-07-03T13:01:00Z"}]
                 (map #(dissoc % :id) (:complementos item)))
              "em ordem cronologica, so' texto e instante")
          (is (every? #(= #{:id :corpo :complementado-em} (set (keys %))) (:complementos item)))
          (is (not (str/includes? (:body r) (str sec))) "quem escreveu nao sai"))
        (testing "outro cidadao nao recebe o protocolo alheio nem o texto do complemento"
          (let [r (pt/response-for cid :get "/portal/meus-protocolos" :headers (como ente intruso))]
            (is (not (str/includes? (:body r) "complemento")))))
        (testing "item sem complemento: lista vazia (nunca ausente)"
          (let [sem (protocolo-respondido! c especie eu sec)
                w (ler (pt/response-for cid :get "/portal/meus-protocolos" :headers (como ente eu)))]
            (is (= [] (:complementos (first (filter #(= (str sem) (:id %)) (get w chave-lista))))))))))))

(deftest as-rotas-publicas-por-protocolo-nao-mostram-o-texto-da-resposta-nem-o-do-complemento
  ;; o protocolo e' sequencial e adivinhavel: a rota publica fica no minimo (protocolo, estado, dias). O texto da RESPOSTA
  ;; nunca aparece nela, e o complemento segue a mesma visibilidade: tambem nao.
  (let [c (cenario) ente (:ente c) sec (random-uuid) eu (random-uuid) svc (servico c #{"secretario"})
        pedido (controllers/protocolar-pedido *repo* (:relogio c) (cidadao ente eu) {:assunto "Folha" :descricao "Quero."})
        manif (controllers/protocolar-manifestacao! *repo* (:relogio c) (cidadao ente eu)
                                                    {:tipo "reclamacao" :assunto "Fila" :descricao "Demora." :anonima false})]
    (controllers/responder-pedido! *repo* (:relogio c) (servidor ente sec) (:id pedido) {:corpo "Resposta."})
    (controllers/responder-manifestacao! *repo* (:relogio c) (servidor ente sec) (:id manif) {:corpo "Resposta."})
    (complementar svc ente sec "esic" (:id pedido) {:corpo "SEGREDO-DO-COMPLEMENTO"})
    (complementar svc ente sec "ouvidoria" (:id manif) {:corpo "SEGREDO-DO-COMPLEMENTO"})
    (doseq [caminho [(str "/portal/casa/" ente "/esic/acompanhar/" (:protocolo pedido))
                     (str "/portal/casa/" ente "/ouvidoria/acompanhar/" (:protocolo manif))]]
      (let [r (pt/response-for svc :get caminho)]
        (is (= 200 (:status r)) caminho)
        (is (= #{:protocolo :estado :dias-restantes} (set (keys (ler r)))))
        (is (not (re-find #"(?i)SEGREDO|complemento" (:body r))) caminho)))))

;; ---------------------------------------------------------------- o banco

(deftest o-complemento-e-so-append-e-isolado-por-casa
  (let [c (cenario) ente (:ente c) sec (random-uuid) svc (servico c #{"secretario"}) ds (:ds *c*)
        id (protocolo-respondido! c :esic (random-uuid) sec)
        cid (parse-uuid (get-in (complementar svc ente sec "esic" id {:corpo "Complemento."}) [:corpo :id]))]
    (testing "outra Casa nao ve a linha (RLS), nem pelo SQL"
      (is (= 1 (count (linhas-do-banco ente "SELECT id FROM participacao.complemento WHERE id = ?" cid))))
      (is (empty? (linhas-do-banco (random-uuid) "SELECT id FROM participacao.complemento WHERE id = ?" cid))))
    (testing "o role da aplicacao nao altera nem apaga (sem GRANT)"
      (doseq [sql ["UPDATE participacao.complemento SET corpo = 'x' WHERE id = ?" "DELETE FROM participacao.complemento WHERE id = ?"]]
        (is (thrown? Exception (tenancy/com-tenant* ds ente #(jdbc/execute! % [sql cid]))) sql)))
    (testing "nem o dono: o trigger append-only recusa UPDATE/DELETE"
      (doseq [sql ["UPDATE participacao.complemento SET corpo = 'x' WHERE id = ?" "DELETE FROM participacao.complemento WHERE id = ?"]]
        (is (thrown? Exception (jdbc/execute! ds [sql cid])) sql)))))

(deftest o-banco-recusa-o-que-a-aplicacao-nunca-grava
  (let [ente (random-uuid) ds (:ds *c*)
        inserir (fn [m]
                  (let [m (merge {:objeto-tipo "pedido_esic" :corpo "Texto."} m)]
                    (tenancy/com-tenant* ds ente
                      #(jdbc/execute! % ["INSERT INTO participacao.complemento (ente_id, objeto_tipo, objeto_id, corpo, complementado_por)
                                          VALUES (?, ?, ?, ?, ?)" ente (:objeto-tipo m) (random-uuid) (:corpo m) (random-uuid)]))))]
    (is (some? (inserir {})) "controle: a linha valida entra")
    (is (thrown? Exception (inserir {:corpo "   "})) "texto nao vazio")
    (is (thrown? Exception (inserir {:corpo (apply str (repeat 50001 "a"))})) "ate' 50000")
    (is (thrown? Exception (inserir {:objeto-tipo "comentario"})) "objeto_tipo dos 3 protocolos")))
