(ns oplenario.participacao.anexos-do-requerente-test
  "INTEGRACAO (PG real + borda HTTP) — o REQUERENTE anexa ao proprio pedido (origem `requerente`). O pedido de e-SIC, a
  manifestacao identificada e a solicitacao LGPD podem nascer com documento (um contrato, uma foto). So' o cidadao DONO do
  protocolo, nos 10 minutos seguintes ao protocolo (`recibo-em`), ate' 5 de origem `requerente` (o limite e' por origem: os
  da Casa nao entram na conta), mesmos tipos/tamanho/erros dos anexos da Casa. Qualquer nao-dono, e a outra Casa, recebe
  404 (sem distinguir 'nao existe' de 'nao e' seu). A manifestacao ANONIMA nao tem dono persistido: nao tem anexo, e NAO ha'
  rota publica de upload (teste sobre a tabela de rotas). Prova tambem: a secretaria baixa o anexo do requerente, o dono
  baixa os seus e os da Casa, `pode-anexar` nos dois lados, e o acompanhamento publico sem anexo."
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
           (java.security MessageDigest)
           (java.time Instant)
           (java.util HexFormat)))

(def ^:dynamic *c* nil)
(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*c* c *repo* (repo-part/->RepoParticipacaoPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

;; ---------------------------------------------------------------- o cenario (o mesmo desenho de anexos-test)

(def ^:private t0 (Instant/parse "2026-07-03T12:00:00Z"))

(defn- cenario []
  (let [instante (atom t0) m (atom {})]
    {:ente (random-uuid) :instante instante :m m
     :relogio (reify tempo/Relogio (agora [_] @instante))
     :store #_{:clj-kondo/ignore [:missing-protocol-method]}
     (reify store/ObjetoStore
       (guardar! [_ k b _] (swap! m assoc k b) k)
       (obter [_ k] (get @m k))
       (abrir [_ k] (some-> (get @m k) ByteArrayInputStream.))
       (remover! [_ k] (swap! m dissoc k) nil)
       (listar [_ prefixo _] (vec (sort (filter #(str/starts-with? % prefixo) (keys @m))))))}))

(defn- avancar! [c segundos] (swap! (:instante c) #(.plusSeconds ^Instant % (long segundos))))

(defn- cidadao [ente iid] {:ente-id ente :identidade-id iid :papeis #{}})
(defn- servidor [ente iid] {:ente-id ente :identidade-id iid :papeis #{"secretario"}})

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- deps [c papeis]
  {:auth (it/autenticacao (idp-dev/idp-dev) (fake-repo-identidade papeis))
   :repo-participacao *repo* :relogio (:relogio c) :objeto-store (:store c)
   :resolver-ente-publico participacao-http/resolver-ente-publico-uuid})

(defn- servico [c papeis]
  (-> (http/servico (config/carregar) (participacao-http/rotas (deps c papeis)) it/globais)
      ph/create-server ::ph/service-fn))

(defn- como [ente iid]
  {"authorization" (str "Bearer " (json/write-value-as-string {:sub "u" :ente-id (str ente) :identidade-id (str iid)}))})

(defn- ler
  "O corpo como JSON; tolerante: um 404 de rota que nao existe devolve texto, e o teste deve olhar o STATUS."
  [r]
  (when (seq (:body r))
    (try (json/read-value (:body r) json/keyword-keys-object-mapper) (catch Exception _ nil))))

(defn- multipart [nome tipo conteudo]
  (let [fronteira "----oplenario-teste-anexo"]
    {:ct (str "multipart/form-data; boundary=" fronteira)
     :body (str "--" fronteira "\r\n"
                "Content-Disposition: form-data; name=\"arquivo\"; filename=\"" nome "\"\r\n"
                "Content-Type: " tipo "\r\n\r\n"
                conteudo "\r\n"
                "--" fronteira "--\r\n")}))

(defn- enviar [svc caminho cabecalhos nome tipo conteudo]
  (let [{:keys [ct body]} (multipart nome tipo conteudo)
        r (pt/response-for svc :post caminho :headers (assoc cabecalhos "Content-Type" ct) :body body)]
    {:status (:status r) :corpo (ler r) :bruto (:body r)}))

(defn- anexar-meu
  "POST /portal/meus-protocolos/<rota>/:id/anexos como `quem` (o cidadao)."
  [svc ente quem rota id nome tipo conteudo]
  (enviar svc (str "/portal/meus-protocolos/" rota "/" id "/anexos") (como ente quem) nome tipo conteudo))

(defn- anexar-da-casa [svc ente quem rota id nome tipo conteudo]
  (enviar svc (str "/atendimento/" rota "/" id "/anexos") (como ente quem) nome tipo conteudo))

(defn- sha256 [^String s] (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))))

(defn- linhas-do-banco [ente sql & params]
  (tenancy/com-tenant* (:ds *c*) ente #(jdbc/execute! % (into [sql] params) {:builder-fn rs/as-unqualified-maps})))

(def ^:private especies
  {:esic {:rota "esic" :tipo-banco "pedido_esic" :chave-lista :pedidos-esic
          :protocolar (fn [c eu] (controllers/protocolar-pedido *repo* (:relogio c) (cidadao (:ente c) eu)
                                                                {:assunto "Folha" :descricao "Quero a folha."}))
          :responder! (fn [c sec id] (controllers/responder-pedido! *repo* (:relogio c) (servidor (:ente c) sec) id {:corpo "Segue."}))}
   :ouvidoria {:rota "ouvidoria" :tipo-banco "manifestacao_ouvidoria" :chave-lista :manifestacoes
               :protocolar (fn [c eu] (controllers/protocolar-manifestacao! *repo* (:relogio c) (cidadao (:ente c) eu)
                                                                            {:tipo "reclamacao" :assunto "Fila" :descricao "Demora." :anonima false}))
               :responder! (fn [c sec id] (controllers/responder-manifestacao! *repo* (:relogio c) (servidor (:ente c) sec) id {:corpo "Segue."}))}
   :lgpd {:rota "lgpd" :tipo-banco "solicitacao_titular" :chave-lista :solicitacoes-lgpd
          :protocolar (fn [c eu] (controllers/solicitar-titular! *repo* (:relogio c) (cidadao (:ente c) eu)
                                                                 {:tipo "acessar" :detalhe "Meus dados."}))
          :responder! (fn [c sec id] (controllers/responder-solicitacao! *repo* (:relogio c) (servidor (:ente c) sec) id {:corpo "Segue."}))}})

(defn- protocolar-completo
  "Protocola (como `eu`) e devolve o recibo ({:id :protocolo :recibo-em})."
  [c especie eu]
  ((get-in especies [especie :protocolar]) c eu))

(defn- protocolar! [c especie eu] (:id (protocolar-completo c especie eu)))

(defn- meu-item
  "O item do protocolo `id` na lista do dono (GET /portal/meus-protocolos), pela borda."
  [svc ente eu especie id]
  (let [w (ler (pt/response-for svc :get "/portal/meus-protocolos" :headers (como ente eu)))]
    (first (filter #(= (str id) (:id %)) (get w (get-in especies [especie :chave-lista]))))))

(def ^:private pdf "%PDF-1.4 contrato de limpeza de 2025")

;; ---------------------------------------------------------------- o dono anexa

(deftest o-dono-anexa-ao-proprio-protocolo-nas-tres-especies
  (doseq [especie [:esic :ouvidoria :lgpd]]
    (testing (name especie)
      (let [c (cenario) ente (:ente c) eu (random-uuid) svc (servico c #{})
            {:keys [rota tipo-banco]} (especies especie)
            id (protocolar! c especie eu)
            r (anexar-meu svc ente eu rota id "contrato.pdf" "application/pdf" pdf)]
        (is (= 201 (:status r)))
        (testing "o 201 so' diz o que a tela precisa; a origem e' `requerente`"
          (is (= #{:id :nome :tipo-midia :bytes :origem :enviado-em} (set (keys (:corpo r)))))
          (is (= {:nome "contrato.pdf" :tipo-midia "application/pdf" :origem "requerente"}
                 (select-keys (:corpo r) [:nome :tipo-midia :origem])))
          (is (not (str/includes? (:bruto r) (str eu))) "nem quem enviou sai")
          (is (not (str/includes? (:bruto r) "atendimento/"))))
        (testing "blob na chave da convencao, sha256 dos bytes, `enviado_por` = o dono (do ATOR, nunca do corpo)"
          (let [anexo-id (get-in r [:corpo :id])
                chave (str "atendimento/" ente "/" id "/" anexo-id)
                [linha] (linhas-do-banco ente "SELECT * FROM participacao.anexo WHERE id = ?::uuid" anexo-id)]
            (is (= pdf (String. ^bytes (get @(:m c) chave) "UTF-8")))
            (is (= [(sha256 pdf) chave tipo-banco id "requerente" eu]
                   [(:sha256 linha) (:chave_objeto linha) (:objeto_tipo linha) (:objeto_id linha) (:origem linha) (:enviado_por linha)]))))))))

(deftest nao-dono-e-outra-casa-recebem-404-sem-rastro
  (let [c (cenario) ente (:ente c) dono (random-uuid) intruso (random-uuid) svc (servico c #{})
        id (protocolar! c :esic dono)]
    (testing "outro cidadao da MESMA Casa"
      (is (= 404 (:status (anexar-meu svc ente intruso "esic" id "x.pdf" "application/pdf" pdf)))))
    (testing "o dono, mas numa OUTRA Casa (a RLS isola o protocolo)"
      (is (= 404 (:status (anexar-meu svc (random-uuid) dono "esic" id "x.pdf" "application/pdf" pdf)))))
    (testing "protocolo que nao existe"
      (is (= 404 (:status (anexar-meu svc ente dono "esic" (random-uuid) "x.pdf" "application/pdf" pdf)))))
    (testing "a especie errada na URL (o id de um pedido numa rota de ouvidoria)"
      (is (= 404 (:status (anexar-meu svc ente dono "ouvidoria" id "x.pdf" "application/pdf" pdf)))))
    (testing "o tipo recusado de quem nao e' dono e' 404, nunca 415 (nao confirma nada)"
      (is (= 404 (:status (anexar-meu svc ente intruso "esic" id "programa.exe" "application/pdf" pdf)))))
    (is (empty? @(:m c)) "nenhum blob")
    (is (empty? (linhas-do-banco ente "SELECT id FROM participacao.anexo")))))

(deftest o-servidor-que-nao-e-o-dono-tambem-recebe-404-nesta-rota
  ;; a secretaria anexa pela rota DELA (do balcao, a resposta); esta rota e' do requerente
  (let [c (cenario) ente (:ente c) dono (random-uuid) sec (random-uuid) svc (servico c #{"secretario"})
        id (protocolar! c :esic dono)]
    (is (= 404 (:status (anexar-meu svc ente sec "esic" id "x.pdf" "application/pdf" pdf))))))

;; ---------------------------------------------------------------- a manifestacao anonima

(deftest a-manifestacao-anonima-nao-tem-anexo-nem-rota-publica-de-upload
  (let [c (cenario) ente (:ente c) autora (random-uuid) svc (servico c #{})
        anon (:id (controllers/protocolar-manifestacao! *repo* (:relogio c) (cidadao ente autora)
                                                       {:tipo "denuncia" :assunto "Obra" :descricao "Parada." :anonima true}))]
    (testing "nem a propria autora anexa (nao ha' dono persistido): 404"
      (is (= 404 (:status (anexar-meu svc ente autora "ouvidoria" anon "x.pdf" "application/pdf" pdf)))))
    (is (empty? @(:m c)))
    (testing "sem token: 401 na rota do cidadao; e rotas publicas de upload simplesmente nao existem (404)"
      (let [{:keys [ct body]} (multipart "x.pdf" "application/pdf" pdf)]
        (is (= 401 (:status (pt/response-for svc :post (str "/portal/meus-protocolos/ouvidoria/" anon "/anexos")
                                             :headers {"Content-Type" ct} :body body))))
        (doseq [caminho [(str "/portal/casa/" ente "/ouvidoria/acompanhar/OUV-2026-000001/anexos")
                         (str "/portal/casa/" ente "/anexos")
                         (str "/portal/ouvidoria/manifestacoes/" anon "/anexos")
                         (str "/portal/ouvidoria/anexos")]]
          (is (= 404 (:status (pt/response-for svc :post caminho :headers {"Content-Type" ct} :body body))) caminho))))))

(deftest toda-rota-de-upload-passa-pelo-login-e-so-existem-as-seis
  ;; A tabela de rotas e' a prova: se alguem montar um upload SEM o `auth` (publico), este teste reprova.
  (let [c (cenario) d (deps c #{}) auth (:auth d)
        uploads (filter (fn [[caminho verbo]] (and (= :post verbo) (str/includes? caminho "/anexos")))
                        (participacao-http/rotas d))]
    (is (= #{"/atendimento/esic/:id/anexos" "/atendimento/ouvidoria/:id/anexos" "/atendimento/lgpd/:id/anexos"
             "/portal/meus-protocolos/esic/:id/anexos" "/portal/meus-protocolos/ouvidoria/:id/anexos"
             "/portal/meus-protocolos/lgpd/:id/anexos"}
           (set (map first uploads)))
        "tres do balcao (secretario) e tres do requerente; nenhuma sob /portal/casa (publico)")
    (is (every? (fn [[_ _ cadeia]] (some #(identical? auth %) cadeia)) uploads) "todas exigem a autenticacao")))

;; ---------------------------------------------------------------- as recusas (as mesmas dos anexos da Casa)

(deftest janela-de-dez-minutos-a-partir-do-protocolo
  (let [c (cenario) ente (:ente c) eu (random-uuid) svc (servico c #{})
        id (protocolar! c :esic eu)]
    (avancar! c 600)
    (is (= 201 (:status (anexar-meu svc ente eu "esic" id "no-limite.pdf" "application/pdf" pdf))) "o minuto 10 ainda vale")
    (avancar! c 1)
    (let [r (anexar-meu svc ente eu "esic" id "tarde.pdf" "application/pdf" pdf)]
      (is (= 409 (:status r)))
      (is (re-find #"10 minutos depois do protocolo" (get-in r [:corpo :erro]))))
    (is (= 1 (count @(:m c))) "o recusado nao deixou blob")))

(deftest o-sexto-anexo-do-requerente-409-sem-blob-orfao-e-o-limite-e-por-origem
  (let [c (cenario) ente (:ente c) eu (random-uuid) sec (random-uuid) svc (servico c #{"secretario"})
        cid (servico c #{})
        id (protocolar! c :esic eu)]
    (dotimes [i 5] (is (= 201 (:status (anexar-meu cid ente eu "esic" id (str "meu" i ".txt") "text/plain" "x")))))
    (let [r (anexar-meu cid ente eu "esic" id "sexto.txt" "text/plain" "x")]
      (is (= 409 (:status r)))
      (is (re-find #"5 anexos seus" (get-in r [:corpo :erro]))))
    (is (= 5 (count @(:m c))) "5 blobs, nao 6")
    (testing "os 5 do requerente NAO tomam a vaga da Casa (e vice-versa): a Casa responde e anexa os seus 5"
      ((get-in especies [:esic :responder!]) c sec id)
      (dotimes [i 5] (is (= 201 (:status (anexar-da-casa svc ente sec "esic" id (str "casa" i ".txt") "text/plain" "y")))))
      (is (= 409 (:status (anexar-da-casa svc ente sec "esic" id "casa-6.txt" "text/plain" "y"))))
      (is (= 10 (count @(:m c)))))))

(deftest tamanho-413-tipo-415-e-sem-arquivo-400
  (let [c (cenario) ente (:ente c) eu (random-uuid) svc (servico c #{}) id (protocolar! c :lgpd eu)]
    (is (= 413 (:status (anexar-meu svc ente eu "lgpd" id "grande.txt" "text/plain" (apply str (repeat (inc (* 10 1024 1024)) "a"))))))
    (is (= 400 (:status (anexar-meu svc ente eu "lgpd" id "vazio.txt" "text/plain" ""))))
    (is (= 400 (:status (pt/response-for svc :post (str "/portal/meus-protocolos/lgpd/" id "/anexos")
                                         :headers (assoc (como ente eu) "Content-Type" "application/json") :body "{}"))))
    (doseq [[nome tipo] [["programa.exe" "application/pdf"] ["pagina.html" "text/html"] ["folha.pdf" "text/plain"]
                         ["foto.png" "image/jpeg"] ["semextensao" "application/pdf"]]]
      (let [r (anexar-meu svc ente eu "lgpd" id nome tipo "conteudo")]
        (is (= 415 (:status r)) (str nome " " tipo))
        (is (re-find #"Aceitamos PDF, PNG, JPEG, TXT, CSV, DOCX, XLSX, ODT e ODS" (get-in r [:corpo :erro])))))
    (is (empty? @(:m c)) "nenhum blob")))

;; ---------------------------------------------------------------- download: as duas origens

(deftest o-dono-baixa-os-seus-e-os-da-casa-e-a-secretaria-baixa-os-dos-dois
  (doseq [especie [:esic :ouvidoria :lgpd]]
    (testing (name especie)
      (let [c (cenario) ente (:ente c) eu (random-uuid) sec (random-uuid) outro (random-uuid)
            cid (servico c #{}) sec-svc (servico c #{"secretario"})
            {:keys [rota responder!]} (especies especie)
            id (protocolar! c especie eu)
            meu-id (get-in (anexar-meu cid ente eu rota id "meu documento.csv" "text/csv" "a;b\n1;2") [:corpo :id])
            _ (responder! c sec id)
            da-casa-id (get-in (anexar-da-casa sec-svc ente sec rota id "resposta.csv" "text/csv" "c;d\n3;4") [:corpo :id])
            meu (fn [aid] (str "/portal/meus-protocolos/" rota "/" id "/anexos/" aid))
            balcao (fn [aid] (str "/atendimento/" rota "/" id "/anexos/" aid))]
        (testing "o dono baixa o SEU e o da Casa, pela mesma rota, como arquivo"
          (let [r (pt/response-for cid :get (meu meu-id) :headers (como ente eu))]
            (is (= 200 (:status r)))
            (is (= "a;b\n1;2" (:body r)))
            (is (str/starts-with? (get-in r [:headers "Content-Disposition"]) "attachment; filename=\"meu documento.csv\""))
            (is (= "nosniff" (get-in r [:headers "X-Content-Type-Options"]))))
          (is (= "c;d\n3;4" (:body (pt/response-for cid :get (meu da-casa-id) :headers (como ente eu))))))
        (testing "a secretaria baixa o do requerente e o da Casa, pelo balcao"
          (is (= "a;b\n1;2" (:body (pt/response-for sec-svc :get (balcao meu-id) :headers (como ente sec)))))
          (is (= "c;d\n3;4" (:body (pt/response-for sec-svc :get (balcao da-casa-id) :headers (como ente sec))))))
        (testing "outro cidadao: 404 nos dois; anonimo: 401"
          (is (= 404 (:status (pt/response-for cid :get (meu meu-id) :headers (como ente outro)))))
          (is (= 404 (:status (pt/response-for cid :get (meu da-casa-id) :headers (como ente outro)))))
          (is (= 401 (:status (pt/response-for cid :get (meu meu-id))))))
        (testing "o cidadao nao baixa pela rota da secretaria"
          (is (= 403 (:status (pt/response-for cid :get (balcao meu-id) :headers (como ente eu))))))))))

;; ---------------------------------------------------------------- pode-anexar, nos dois lados

(deftest pode-anexar-do-requerente-em-meus-protocolos
  (let [c (cenario) ente (:ente c) eu (random-uuid) svc (servico c #{}) id (protocolar! c :esic eu)
        item #(meu-item svc ente eu :esic id)]
    (testing "recem protocolado, sem anexo: cabe"
      (is (true? (:pode-anexar (item)))))
    (testing "com anexos, os da Casa nao contam: a lista traz a origem de cada um; ao chegar em 5 do requerente, nao cabe"
      (dotimes [i 4] (anexar-meu svc ente eu "esic" id (str "a" i ".txt") "text/plain" "x"))
      (is (true? (:pode-anexar (item))) "4 de 5")
      (anexar-meu svc ente eu "esic" id "a4.txt" "text/plain" "x")
      (let [i (item)]
        (is (false? (:pode-anexar i)))
        (is (= ["requerente"] (distinct (map :origem (:anexos i)))))
        (is (= 5 (count (:anexos i))))
        (is (every? #(= #{:id :nome :tipo-midia :bytes :origem :enviado-em} (set (keys %))) (:anexos i)))))
    (testing "passada a janela, nao cabe (outro protocolo, sem anexos)"
      (let [id2 (protocolar! c :lgpd eu)]
        (is (true? (:pode-anexar (meu-item svc ente eu :lgpd id2))))
        (avancar! c 601)
        (is (false? (:pode-anexar (meu-item svc ente eu :lgpd id2))))))))

(deftest o-balcao-ve-as-duas-origens-e-o-pode-anexar-da-casa-nao-muda
  (let [c (cenario) ente (:ente c) eu (random-uuid) sec (random-uuid) cid (servico c #{}) sec-svc (servico c #{"secretario"})
        id (protocolar! c :esic eu)
        detalhe #(ler (pt/response-for sec-svc :get (str "/atendimento/esic/" id) :headers (como ente sec)))]
    (anexar-meu cid ente eu "esic" id "do-pedido.pdf" "application/pdf" pdf)
    (testing "sem resposta, a Casa ainda nao pode anexar (os anexos do requerente nao abrem a janela dela)"
      (let [d (detalhe)]
        (is (false? (get-in d [:acoes :pode-anexar])))
        (is (= [["do-pedido.pdf" "requerente"]] (map (juxt :nome :origem) (:anexos d))))))
    ((get-in especies [:esic :responder!]) c sec id)
    (anexar-da-casa sec-svc ente sec "esic" id "da-resposta.pdf" "application/pdf" pdf)
    (let [d (detalhe)]
      (is (= #{["do-pedido.pdf" "requerente"] ["da-resposta.pdf" "casa"]} (set (map (juxt :nome :origem) (:anexos d)))))
      (is (true? (get-in d [:acoes :pode-anexar])) "1 da Casa de 5: cabe")
      (is (every? #(= #{:id :nome :tipo-midia :bytes :origem :enviado-em} (set (keys %))) (:anexos d)))
      (is (not (str/includes? (json/write-value-as-string d) (str eu))) "quem enviou o anexo do requerente nao sai"))
    (testing "5 anexos do requerente nao impedem a Casa (o limite e' por origem)"
      (dotimes [i 4] (anexar-meu cid ente eu "esic" id (str "m" i ".txt") "text/plain" "x"))
      (is (true? (get-in (detalhe) [:acoes :pode-anexar]))))))

;; ---------------------------------------------------------------- o publico nao ve anexo

(deftest acompanhamento-publico-nao-carrega-anexo-do-requerente
  (let [c (cenario) ente (:ente c) eu (random-uuid) cid (servico c #{})
        pedido (protocolar-completo c :esic eu)
        manif (controllers/protocolar-manifestacao! *repo* (:relogio c) (cidadao ente eu)
                                                    {:tipo "reclamacao" :assunto "Fila" :descricao "Demora." :anonima false})]
    (is (= 201 (:status (anexar-meu cid ente eu "esic" (:id pedido) "SEGREDO-DO-REQUERENTE.pdf" "application/pdf" pdf))))
    (is (= 201 (:status (anexar-meu cid ente eu "ouvidoria" (:id manif) "SEGREDO-DO-REQUERENTE.pdf" "application/pdf" pdf))))
    (testing "controle positivo: o nome aparece para o dono"
      (is (str/includes? (:body (pt/response-for cid :get "/portal/meus-protocolos" :headers (como ente eu))) "SEGREDO-DO-REQUERENTE")))
    (doseq [caminho [(str "/portal/casa/" ente "/esic/acompanhar/" (:protocolo pedido))
                     (str "/portal/casa/" ente "/ouvidoria/acompanhar/" (:protocolo manif))]]
      (let [r (pt/response-for cid :get caminho)]
        (is (= 200 (:status r)) caminho)
        (is (= #{:protocolo :estado :dias-restantes} (set (keys (ler r)))))
        (is (not (re-find #"(?i)anexo|SEGREDO|\.pdf|atendimento/" (:body r))) caminho)))))
