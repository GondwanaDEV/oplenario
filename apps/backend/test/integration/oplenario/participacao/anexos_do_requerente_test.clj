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
            [io.pedestal.interceptor :as pi]
            [io.pedestal.interceptor.chain :as chain]
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
            [oplenario.auditoria.logic :as auditoria]
            [oplenario.participacao.controllers :as controllers]
            [oplenario.participacao.diplomat.http.in :as participacao-http]
            [oplenario.participacao.logic.anexo :as anexo-logic])
  (:import (java.io ByteArrayInputStream FilterInputStream InputStream SequenceInputStream)
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
                (when tipo (str "Content-Type: " tipo "\r\n")) "\r\n"
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
        uploads (filter (fn [[caminho verbo]] (and (= :post verbo) (str/includes? caminho "/anexos")
                                                 (not (str/ends-with? caminho "/retirar"))      ; retirar nao e' upload
                                                 (not (str/ends-with? caminho "/substituir"))))   ; substituir e' do balcao: tem teste proprio (anexos-substituir-test)
                        (participacao-http/rotas d))]
    (is (= #{"/atendimento/esic/:id/anexos" "/atendimento/ouvidoria/:id/anexos" "/atendimento/lgpd/:id/anexos"
             "/portal/meus-protocolos/esic/:id/anexos" "/portal/meus-protocolos/ouvidoria/:id/anexos"
             "/portal/meus-protocolos/lgpd/:id/anexos"}
           (set (map first uploads)))
        "tres do balcao (secretario) e tres do requerente; nenhuma sob /portal/casa (publico)")
    (is (every? (fn [[_ _ cadeia]] (some #(identical? auth %) cadeia)) uploads) "todas exigem a autenticacao")
    (testing "as rotas de RETIRAR (B4) sao do balcao: autenticacao e papel secretario, nunca publicas"
      (let [retirar (filter (fn [[caminho verbo]] (and (= :post verbo) (str/ends-with? caminho "/retirar")))
                            (participacao-http/rotas d))]
        (is (= #{"/atendimento/esic/:id/anexos/:anexo/retirar" "/atendimento/ouvidoria/:id/anexos/:anexo/retirar"
                 "/atendimento/lgpd/:id/anexos/:anexo/retirar"}
               (set (map first retirar))))
        (is (every? (fn [[_ _ cadeia]] (some #(identical? auth %) cadeia)) retirar))
        (is (every? (fn [[_ _ cadeia]] (some #(= (:name (it/exige-papel "secretario")) (:name %)) (filter map? cadeia))) retirar))))))

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
    (dotimes [i 5] (is (= 201 (:status (anexar-meu cid ente eu "esic" id (str "meu" i ".txt") "text/plain" (str "x" i))))))
    (let [r (anexar-meu cid ente eu "esic" id "sexto.txt" "text/plain" "x-sexto")]
      (is (= 409 (:status r)))
      (is (re-find #"5 anexos seus" (get-in r [:corpo :erro]))))
    (is (= 5 (count @(:m c))) "5 blobs, nao 6")
    (testing "os 5 do requerente NAO tomam a vaga da Casa (e vice-versa): a Casa responde e anexa os seus 5"
      ((get-in especies [:esic :responder!]) c sec id)
      (dotimes [i 5] (is (= 201 (:status (anexar-da-casa svc ente sec "esic" id (str "casa" i ".txt") "text/plain" (str "y" i))))))
      (is (= 409 (:status (anexar-da-casa svc ente sec "esic" id "casa-6.txt" "text/plain" "y-sexto"))))
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
      (dotimes [i 4] (anexar-meu svc ente eu "esic" id (str "a" i ".txt") "text/plain" (str "x" i)))
      (is (true? (:pode-anexar (item))) "4 de 5")
      (anexar-meu svc ente eu "esic" id "a4.txt" "text/plain" "x4")
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
      (dotimes [i 4] (anexar-meu cid ente eu "esic" id (str "m" i ".txt") "text/plain" (str "x" i)))
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

;; =============================================================================================================
;; RODADA DE CORRECOES (revisao adversarial): o upload nao le o corpo antes de saber se o pedido cabe (B1), a cota de
;; disco (B2), retirar anexo (B4), nome/tipo/assinatura (B5-B7), reenvio idempotente (B8), linha sem blob (B9),
;; trilha de auditoria (B12).
;; =============================================================================================================

;; ---------------------------------------------------------------- B1: a cadeia da rota, com um corpo que CONTA o que le

(def ^:private fronteira "----oplenario-teste-anexo")

(defn- bytes-de [^String x] (.getBytes x "UTF-8"))

(defn- enchimento
  "Um fluxo de `n` bytes 'a' que nao aloca (um upload de 10 MB sem 10 MB de heap no teste)."
  ^InputStream [n]
  (let [restante (atom (long n))]
    (proxy [InputStream] []
      (read
        ([] (if (pos? @restante) (do (swap! restante dec) 97) -1))
        ([^bytes b off len]
         (let [r @restante]
           (if (<= r 0)
             -1
             (let [k (int (min r len))]
               (java.util.Arrays/fill b (int off) (int (+ off k)) (byte 97))
               (swap! restante - k)
               k))))))))

(defn- parte-de-arquivo [nome tipo]
  (str "--" fronteira "\r\nContent-Disposition: form-data; name=\"arquivo\"; filename=\"" nome "\"\r\n"
       (when tipo (str "Content-Type: " tipo "\r\n")) "\r\n"))

(defn- parte-de-campo [nome] (str "--" fronteira "\r\nContent-Disposition: form-data; name=\"" nome "\"\r\n\r\n"))

(defn- corpo-contado
  "O corpo como FLUXO que conta os bytes lidos. `partes` = sequencia de String | [:enche n] (n bytes de enchimento).
  `ao-ler` roda UMA vez, na primeira leitura (o teste usa para andar o relogio no meio do upload)."
  [partes contador & [ao-ler]]
  (let [fluxos (map (fn [p] (if (string? p) (ByteArrayInputStream. (bytes-de p)) (enchimento (second p)))) partes)
        bruto (SequenceInputStream. (java.util.Collections/enumeration fluxos))
        primeiro (atom true)
        conta! (fn [n] (when (pos? n) (swap! contador + n)) n)
        avisa! (fn [] (when (and ao-ler (compare-and-set! primeiro true false)) (ao-ler)))]
    (proxy [FilterInputStream] [bruto]
      (read
        ([] (avisa!) (let [b (.read ^InputStream bruto)] (when (>= b 0) (conta! 1)) b))
        ([^bytes b off len] (avisa!) (conta! (.read ^InputStream bruto b (int off) (int len))))))))

(defn- tamanho-dos [partes] (reduce + (map (fn [p] (if (string? p) (count (bytes-de p)) (second p))) partes)))

(defn- arquivo-gigante [n & {:keys [nome tipo] :or {nome "grande.pdf" tipo "application/pdf"}}]
  [(str (parte-de-arquivo nome tipo) "%PDF-1.4 ") [:enche n] (str "\r\n--" fronteira "--\r\n")])

(defn- rota-da-cadeia [c papeis nome-da-rota]
  (some (fn [[_ _ cadeia _ nome]] (when (= nome-da-rota nome) cadeia)) (participacao-http/rotas (deps c papeis))))

(defn- executar-cadeia
  "Roda os interceptors da rota `nome-da-rota` (a MESMA cadeia do servidor) sobre `req` e devolve a resposta."
  [c papeis nome-da-rota req]
  (:response (chain/execute {:request req} (mapv pi/interceptor (rota-da-cadeia c papeis nome-da-rota)))))

(defn- requisicao-de-upload
  ([ente quem id partes contador] (requisicao-de-upload ente quem id partes contador nil))
  ([ente quem id partes contador ao-ler & {:keys [com-tamanho?] :or {com-tamanho? true}}]
   {:request-method :post
    :path-params {:id (str id)}
    :headers (cond-> (assoc (como ente quem) "content-type" (str "multipart/form-data; boundary=" fronteira))
               com-tamanho? (assoc "content-length" (str (tamanho-dos partes))))
    :body (corpo-contado partes contador ao-ler)}))

(def ^:private dez-mb (* 10 1024 1024))

(deftest o-pedido-que-nao-cabe-e-recusado-sem-ler-o-corpo
  ;; B1a: antes do multipart, um interceptor confere (sem tocar no corpo) que o alvo existe e e' da Casa, que quem pede e'
  ;; o dono (404 uniforme), que a janela esta aberta e o limite nao foi atingido (409). Um corpo de 10 MB de quem nao pode
  ;; anexar nunca entra na memoria: o fluxo conta os bytes lidos e o numero e' ZERO.
  (let [c (cenario) ente (:ente c) dono (random-uuid) intruso (random-uuid) sec (random-uuid)
        cid (servico c #{}) id (protocolar! c :esic dono)
        tentar (fn [papeis rota-nome ente* quem id*]
                 (let [lido (atom 0)
                       r (executar-cadeia c papeis rota-nome (requisicao-de-upload ente* quem id* (arquivo-gigante dez-mb) lido))]
                   [(:status r) @lido]))]
    (testing "controle positivo: o dono com um arquivo pequeno anexa, e o corpo foi lido (o contador funciona)"
      (let [lido (atom 0)
            r (executar-cadeia c #{} :participacao/anexar-meu-esic
                               (requisicao-de-upload ente dono id [(str (parte-de-arquivo "ok.pdf" "application/pdf") pdf "\r\n--" fronteira "--\r\n")] lido))]
        (is (= 201 (:status r)))
        (is (pos? @lido))))
    (testing "cidadao que nao e' o dono: 404, ZERO bytes lidos"
      (is (= [404 0] (tentar #{} :participacao/anexar-meu-esic ente intruso id))))
    (testing "o dono numa outra Casa, e protocolo inexistente: 404, ZERO bytes"
      (is (= [404 0] (tentar #{} :participacao/anexar-meu-esic (random-uuid) dono id)))
      (is (= [404 0] (tentar #{} :participacao/anexar-meu-esic ente dono (random-uuid)))))
    (testing "a manifestacao anonima nao tem dono: 404, ZERO bytes"
      (let [anon (:id (controllers/protocolar-manifestacao! *repo* (:relogio c) (cidadao ente dono)
                                                           {:tipo "denuncia" :assunto "Obra" :descricao "Parada." :anonima true}))]
        (is (= [404 0] (tentar #{} :participacao/anexar-meu-ouvidoria ente dono anon)))))
    (testing "o dono com o limite de 5 atingido: 409, ZERO bytes"
      (dotimes [i 4] (is (= 201 (:status (anexar-meu cid ente dono "esic" id (str "m" i ".txt") "text/plain" (str "x" i))))))
      (is (= [409 0] (tentar #{} :participacao/anexar-meu-esic ente dono id)) "com 5 (1 do controle + 4) ja' nao cabe"))
    (testing "o dono passada a janela de 10 minutos: 409, ZERO bytes"
      (let [id2 (protocolar! c :lgpd dono)]
        (avancar! c 601)
        (is (= [409 0] (tentar #{} :participacao/anexar-meu-lgpd ente dono id2)))))
    (testing "o balcao: protocolo sem resposta da Casa, 409; passada a janela, 409; no limite, 409 — sempre ZERO bytes"
      (let [c2 (cenario) e2 (:ente c2) id2 (protocolar! c2 :esic (random-uuid))
            tentar2 (fn [] (let [lido (atom 0)
                                 r (executar-cadeia c2 #{"secretario"} :participacao/anexar-esic
                                                    (requisicao-de-upload e2 sec id2 (arquivo-gigante dez-mb) lido))]
                             [(:status r) @lido]))
            svc2 (servico c2 #{"secretario"})]
        (is (= [409 0] (tentar2)) "sem resposta da Casa")
        ((get-in especies [:esic :responder!]) c2 sec id2)
        (dotimes [i 5] (anexar-da-casa svc2 e2 sec "esic" id2 (str "c" i ".txt") "text/plain" (str "y" i)))
        (is (= [409 0] (tentar2)) "5 anexos da Casa: o limite")
        (avancar! c2 601)
        (is (= [409 0] (tentar2)) "passou a janela")))
    (testing "o papel e' conferido antes de tudo (403, sem ler): nao-secretario no balcao"
      (let [lido (atom 0)
            r (try (executar-cadeia c #{} :participacao/anexar-esic (requisicao-de-upload ente dono id (arquivo-gigante dez-mb) lido))
                   (catch Exception e {:status (:status (ex-data e)) :excecao e}))]
        (is (zero? @lido))
        (is (or (= 403 (:status r)) (:excecao r)) "403 pelo interceptor de erro, ou a excecao de negacao: nunca 201")))))

(deftest a-janela-vale-no-instante-da-conferencia-previa
  ;; conexao lenta nao perde a janela: o instante lido ANTES de ler o corpo e' o que vale, mesmo que o upload demore
  ;; mais que os 10 minutos
  (let [c (cenario) ente (:ente c) dono (random-uuid) id (protocolar! c :esic dono)
        lido (atom 0)
        partes [(str (parte-de-arquivo "lento.pdf" "application/pdf") pdf "\r\n--" fronteira "--\r\n")]
        r (executar-cadeia c #{} :participacao/anexar-meu-esic
                           (requisicao-de-upload ente dono id partes lido (fn [] (avancar! c 700))))]
    (is (= 201 (:status r)) "o upload comecou aos 0 s e acabou aos 700 s: a janela foi decidida aos 0 s")
    (testing "mas outro pedido que COMECA depois da janela e' recusado"
      (is (= 409 (:status (executar-cadeia c #{} :participacao/anexar-meu-esic
                                           (requisicao-de-upload ente dono id partes (atom 0)))))))))

(deftest teto-global-de-envios-simultaneos-503-sem-ler-o-corpo
  ;; B1b: um semaforo global (constante nomeada) limita quantos uploads estao lendo corpo ao mesmo tempo
  (let [c (cenario) ente (:ente c) dono (random-uuid) id (protocolar! c :esic dono)
        ^java.util.concurrent.Semaphore vagas @#'it/vagas-de-envio]
    (is (= it/max-envios-simultaneos (.availablePermits vagas)) "tudo livre no comeco")
    (.acquire vagas (.availablePermits vagas))
    (try
      (let [lido (atom 0)
            r (executar-cadeia c #{} :participacao/anexar-meu-esic
                               (requisicao-de-upload ente dono id (arquivo-gigante dez-mb) lido))]
        (is (= 503 (:status r)))
        (is (= "5" (get-in r [:headers "Retry-After"])))
        (is (re-find #"(?i)tente de novo|ocupado|muitos envios" (get-in r [:body])) "mensagem em portugues")
        (is (zero? @lido) "saturado: o corpo nem comecou a ser lido"))
      (finally (.release vagas it/max-envios-simultaneos)))
    (testing "liberada a vaga, o envio passa; e a vaga volta ao fim (nem em 4xx nem em 2xx fica presa)"
      (let [r (executar-cadeia c #{} :participacao/anexar-meu-esic
                               (requisicao-de-upload ente dono id [(str (parte-de-arquivo "ok.pdf" "application/pdf") pdf "\r\n--" fronteira "--\r\n")] (atom 0)))]
        (is (= 201 (:status r)))
        (is (= it/max-envios-simultaneos (.availablePermits vagas)) "a vaga foi devolvida")))
    (testing "uma recusa DENTRO do interceptor (corpo sem arquivo, 400) tambem devolve a vaga"
      (executar-cadeia c #{} :participacao/anexar-meu-esic
                       (requisicao-de-upload ente dono id [(str (parte-de-campo "x") "oi\r\n--" fronteira "--\r\n")] (atom 0)))
      (is (= it/max-envios-simultaneos (.availablePermits vagas))))))

(deftest o-teto-do-corpo-inteiro-e-contado-no-stream-mesmo-sem-content-length
  ;; B1c: corpo chunked (sem Content-Length) com um CAMPO gigante antes do arquivo: a leitura para no teto e responde 413
  (let [c (cenario) ente (:ente c) dono (random-uuid) id (protocolar! c :esic dono)
        lido (atom 0)
        grande (+ dez-mb (* 1024 1024))
        partes [(parte-de-campo "lixo") [:enche grande] (str "\r\n" (parte-de-arquivo "x.pdf" "application/pdf") pdf "\r\n--" fronteira "--\r\n")]
        r (executar-cadeia c #{} :participacao/anexar-meu-esic
                           (requisicao-de-upload ente dono id partes lido nil :com-tamanho? false))]
    (is (= 413 (:status r)))
    (is (< @lido (+ dez-mb (* 128 1024))) "parou perto do teto (10 MB + o envelope): nao leu o 1 MB a mais, nem o resto")
    (testing "o arquivo gigante sem Content-Length tambem: 413"
      (let [lido2 (atom 0)
            r2 (executar-cadeia c #{} :participacao/anexar-meu-esic
                                (requisicao-de-upload ente dono id (arquivo-gigante grande) lido2 nil :com-tamanho? false))]
        (is (= 413 (:status r2)))
        (is (< @lido2 (+ dez-mb (* 128 1024))))))))

(deftest so-um-arquivo-e-poucas-partes-e-para-de-ler-no-segundo-arquivo
  ;; B1d: o 2o arquivo (ou partes demais) -> 400; ao ver o 2o arquivo, para de ler (nao arrasta o resto do corpo)
  (let [c (cenario) ente (:ente c) dono (random-uuid) id (protocolar! c :esic dono)
        enviar-partes (fn [partes] (let [lido (atom 0)
                                         r (executar-cadeia c #{} :participacao/anexar-meu-esic
                                                            (requisicao-de-upload ente dono id partes lido nil :com-tamanho? false))]
                                     [(:status r) @lido]))]
    (testing "dois arquivos: 400, e a leitura para no cabecalho do segundo (o corpo dele, de 5 MB, nao e' lido)"
      (let [[status lido] (enviar-partes [(str (parte-de-arquivo "a.pdf" "application/pdf") pdf "\r\n")
                                          (parte-de-arquivo "b.pdf" "application/pdf") [:enche (* 5 1024 1024)]
                                          (str "\r\n--" fronteira "--\r\n")])]
        (is (= 400 status))
        (is (< lido (* 64 1024)) (str "leu " lido " bytes: o 2o arquivo nao e' lido"))))
    (testing "partes demais (campos + arquivo acima do teto de partes): 400"
      (let [campos (mapv (fn [i] (str (parte-de-campo (str "c" i)) "v\r\n")) (range (inc it/max-partes-do-envio)))
            [status _] (enviar-partes (-> (vec campos)
                                          (conj (str (parte-de-arquivo "a.pdf" "application/pdf") pdf "\r\n--" fronteira "--\r\n"))))]
        (is (= 400 status))))
    (testing "campos de formulario ate' o teto continuam passando"
      (let [campos (mapv (fn [i] (str (parte-de-campo (str "c" i)) "v\r\n")) (range 3))
            [status _] (enviar-partes (-> (vec campos)
                                          (conj (str (parte-de-arquivo "ok.pdf" "application/pdf") pdf "\r\n--" fronteira "--\r\n"))))]
        (is (= 201 status))))))

;; ---------------------------------------------------------------- B5: nome de 10 mil caracteres e lixo no cabecalho

(deftest filename-gigante-e-400-nunca-stack-overflow
  (let [c (cenario) ente (:ente c) dono (random-uuid) svc (servico c #{}) id (protocolar! c :esic dono)]
    (doseq [tamanho [600 5000 10000 50000]]
      (let [r (anexar-meu svc ente dono "esic" id (str (apply str (repeat tamanho "a")) ".pdf") "application/pdf" pdf)]
        (is (contains? #{201 400} (:status r)) (str tamanho " chars: o status e' de cliente, nunca 500"))))
    (let [r (anexar-meu svc ente dono "esic" id (str (apply str (repeat 10000 "a")) ".pdf") "application/pdf" pdf)]
      (is (= 400 (:status r)) "10 000 caracteres: 400"))))

;; ---------------------------------------------------------------- B2: a cota de disco do cidadao (100 MB / 24 h)

(deftest cota-de-disco-do-requerente-em-24-horas
  (with-redefs [anexo-logic/cota-do-requerente-bytes 100]
    (let [c (cenario) ente (:ente c) eu (random-uuid) outra (random-uuid) sec (random-uuid)
          cid (servico c #{}) sec-svc (servico c #{"secretario"})
          texto (fn [k] (apply str k (repeat 39 "a")))     ; 40 bytes distintos por k
          ids (mapv (fn [_] (protocolar! c :esic eu)) (range 3))]
      (is (= 201 (:status (anexar-meu cid ente eu "esic" (ids 0) "a.txt" "text/plain" (texto "1")))) "40 de 100")
      (is (= 201 (:status (anexar-meu cid ente eu "esic" (ids 1) "b.txt" "text/plain" (texto "2")))) "80 de 100 (outro protocolo, a mesma identidade)")
      (let [r (anexar-meu cid ente eu "esic" (ids 2) "c.txt" "text/plain" (texto "3"))]
        (is (= 409 (:status r)) "120 passa de 100")
        (is (re-find #"(?i)espaço|cota|limite" (get-in r [:corpo :erro])) "mensagem legivel")
        (is (re-find #"24" (get-in r [:corpo :erro])) "diz que e' das ultimas 24 horas"))
      (is (= 2 (count @(:m c))) "o recusado nao deixou blob")
      (testing "e' POR IDENTIDADE: outra pessoa, mesma Casa, tem a cota dela"
        (let [id-outra (protocolar! c :esic outra)]
          (is (= 201 (:status (anexar-meu cid ente outra "esic" id-outra "d.txt" "text/plain" (texto "4")))))))
      (testing "a Casa nao tem cota: a secretaria anexa o que couber no limite de 5"
        (let [id (protocolar! c :esic (random-uuid))]
          ((get-in especies [:esic :responder!]) c sec id)
          (dotimes [i 3] (is (= 201 (:status (anexar-da-casa sec-svc ente sec "esic" id (str "casa" i ".txt") "text/plain"
                                                             (apply str "casa" i (repeat 80 "z")))))))))
      (testing "passadas 24 horas, a cota volta (e a janela de 10 minutos do protocolo novo vale)"
        (avancar! c (+ (* 24 3600) 1))
        (let [id (protocolar! c :esic eu)]
          (is (= 201 (:status (anexar-meu cid ente eu "esic" id "e.txt" "text/plain" (texto "5"))))))))))

;; ---------------------------------------------------------------- B4: retirar anexo

(defn- retirar [svc ente quem rota id anexo-id corpo-json]
  (let [r (pt/response-for svc :post (str "/atendimento/" rota "/" id "/anexos/" anexo-id "/retirar")
                           :headers (assoc (como ente quem) "Content-Type" "application/json") :body corpo-json)]
    {:status (:status r) :corpo (ler r) :bruto (:body r)}))

(defn- motivo [m] (json/write-value-as-string {:motivo m}))

(deftest retirar-anexo-das-duas-origens
  (doseq [especie [:esic :ouvidoria :lgpd]]
    (testing (name especie)
      (let [c (cenario) ente (:ente c) eu (random-uuid) sec (random-uuid) cid (servico c #{}) sec-svc (servico c #{"secretario"})
            {:keys [rota responder!]} (especies especie)
            id (protocolar! c especie eu)
            meu (get-in (anexar-meu cid ente eu rota id "meu.pdf" "application/pdf" pdf) [:corpo :id])
            _ (responder! c sec id)
            da-casa (get-in (anexar-da-casa sec-svc ente sec rota id "resposta.pdf" "application/pdf" pdf) [:corpo :id])
            chave #(str "atendimento/" ente "/" id "/" %)]
        (is (every? #(contains? @(:m c) (chave %)) [meu da-casa]))
        (doseq [[aid qual] [[meu "do requerente"] [da-casa "da Casa"]]]
          (testing qual
            (let [r (retirar sec-svc ente sec rota id aid (motivo "contem dado pessoal de terceiros"))]
              (is (= 200 (:status r)) (str (:bruto r)))
              (is (some? (get-in r [:corpo :retirado-em])))
              (is (= "contem dado pessoal de terceiros" (get-in r [:corpo :motivo-da-retirada]))))
            (is (not (contains? @(:m c) (chave aid))) "o blob saiu do object storage")
            (testing "o download vira 404 para todos"
              (is (= 404 (:status (pt/response-for sec-svc :get (str "/atendimento/" rota "/" id "/anexos/" aid) :headers (como ente sec)))))
              (is (= 404 (:status (pt/response-for cid :get (str "/portal/meus-protocolos/" rota "/" id "/anexos/" aid) :headers (como ente eu))))))))
        (testing "o balcao lista como retirado, com a data E o motivo"
          (let [d (ler (pt/response-for sec-svc :get (str "/atendimento/" rota "/" id) :headers (como ente sec)))
                por-nome (into {} (map (juxt :nome identity) (:anexos d)))]
            (is (= 2 (count (:anexos d))) "a linha segue na lista (prova): marcada, nao apagada")
            (is (every? #(some? (:retirado-em %)) (vals por-nome)))
            (is (= "contem dado pessoal de terceiros" (:motivo-da-retirada (por-nome "meu.pdf"))))))
        (testing "o requerente ve que foi retirado e quando, SEM o motivo e sem link"
          (let [r (pt/response-for cid :get "/portal/meus-protocolos" :headers (como ente eu))
                item (first (filter #(= (str id) (:id %)) (get (ler r) (get-in especies [especie :chave-lista]))))]
            (is (= 2 (count (:anexos item))))
            (is (every? #(some? (:retirado-em %)) (:anexos item)))
            (is (every? #(not (contains? % :motivo-da-retirada)) (:anexos item)))
            (is (not (str/includes? (:body r) "dado pessoal de terceiros")) "o motivo so' aparece no balcao")))
        (testing "a retirada e' append-only: 2 linhas, com quem retirou"
          (let [ls (linhas-do-banco ente "SELECT anexo_id, retirado_por, motivo FROM participacao.anexo_retirada ORDER BY retirado_em")]
            (is (= 2 (count ls)))
            (is (every? #(= sec (:retirado_por %)) ls))))))))

(deftest retirar-e-idempotente
  (let [c (cenario) ente (:ente c) eu (random-uuid) sec (random-uuid) cid (servico c #{}) sec-svc (servico c #{"secretario"})
        id (protocolar! c :esic eu)
        aid (get-in (anexar-meu cid ente eu "esic" id "meu.pdf" "application/pdf" pdf) [:corpo :id])
        r1 (retirar sec-svc ente sec "esic" id aid (motivo "primeiro motivo"))
        _ (avancar! c 30)
        r2 (retirar sec-svc ente sec "esic" id aid (motivo "outro motivo, ignorado"))]
    (is (= 200 (:status r1)))
    (is (= 200 (:status r2)))
    (is (= (:corpo r1) (:corpo r2)) "a mesma resposta: a data e o motivo sao os da primeira retirada")
    (is (= 1 (count (linhas-do-banco ente "SELECT 1 FROM participacao.anexo_retirada"))) "sem segunda linha")))

(deftest anexo-retirado-devolve-a-vaga-do-limite
  (let [c (cenario) ente (:ente c) eu (random-uuid) sec (random-uuid) cid (servico c #{}) sec-svc (servico c #{"secretario"})
        id (protocolar! c :esic eu)
        ids (mapv #(get-in (anexar-meu cid ente eu "esic" id (str "m" % ".txt") "text/plain" (str "x" %)) [:corpo :id]) (range 5))]
    (is (false? (:pode-anexar (meu-item cid ente eu :esic id))))
    (is (= 409 (:status (anexar-meu cid ente eu "esic" id "sexto.txt" "text/plain" "x-sexto"))))
    (is (= 200 (:status (retirar sec-svc ente sec "esic" id (first ids) (motivo "enviado por engano")))))
    (is (true? (:pode-anexar (meu-item cid ente eu :esic id))) "o retirado nao conta")
    (is (= 201 (:status (anexar-meu cid ente eu "esic" id "sexto.txt" "text/plain" "x-sexto"))))
    (testing "o balcao: o pode-anexar da Casa tambem ignora o retirado"
      ((get-in especies [:esic :responder!]) c sec id)
      (let [casa (mapv #(get-in (anexar-da-casa sec-svc ente sec "esic" id (str "c" % ".txt") "text/plain" (str "y" %)) [:corpo :id]) (range 5))
            detalhe #(ler (pt/response-for sec-svc :get (str "/atendimento/esic/" id) :headers (como ente sec)))]
        (is (false? (get-in (detalhe) [:acoes :pode-anexar])))
        (retirar sec-svc ente sec "esic" id (first casa) (motivo "versao errada"))
        (is (true? (get-in (detalhe) [:acoes :pode-anexar])))))))

(deftest retirar-exige-motivo-papel-e-alvo-certo
  (let [c (cenario) ente (:ente c) eu (random-uuid) sec (random-uuid) cid (servico c #{}) sec-svc (servico c #{"secretario"})
        id (protocolar! c :esic eu)
        outro (protocolar! c :esic eu)
        aid (get-in (anexar-meu cid ente eu "esic" id "meu.pdf" "application/pdf" pdf) [:corpo :id])
        ainda-la? #(and (contains? @(:m c) (str "atendimento/" ente "/" id "/" aid))
                        (zero? (count (linhas-do-banco ente "SELECT 1 FROM participacao.anexo_retirada"))))]
    (testing "o motivo e' obrigatorio: ausente, vazio ou so' espacos -> 400, e nada muda"
      (doseq [corpo ["{}" (motivo "") (motivo "   ") "{\"motivo\":null}" "nao e json"]]
        (is (= 400 (:status (retirar sec-svc ente sec "esic" id aid corpo))) corpo))
      (is (ainda-la?)))
    (testing "quem nao e' secretario nao retira: 403 (o proprio dono, inclusive)"
      (is (= 403 (:status (retirar cid ente eu "esic" id aid (motivo "quero tirar")))))
      (is (= 403 (:status (retirar (servico c #{"vereador"}) ente sec "esic" id aid (motivo "x")))))
      (is (ainda-la?)))
    (testing "outra Casa, protocolo errado, anexo inexistente, especie errada: 404, nada muda"
      (is (= 404 (:status (retirar (servico c #{"secretario"}) (random-uuid) sec "esic" id aid (motivo "x")))))
      (is (= 404 (:status (retirar sec-svc ente sec "esic" outro aid (motivo "x"))))
          "o anexo e' de outro protocolo da mesma Casa")
      (is (= 404 (:status (retirar sec-svc ente sec "esic" id (random-uuid) (motivo "x")))))
      (is (= 404 (:status (retirar sec-svc ente sec "ouvidoria" id aid (motivo "x")))))
      (is (ainda-la?)))
    (testing "sem token: 401"
      (is (= 401 (:status (pt/response-for sec-svc :post (str "/atendimento/esic/" id "/anexos/" aid "/retirar")
                                           :headers {"Content-Type" "application/json"} :body (motivo "x"))))))))

;; ---------------------------------------------------------------- B6: o tipo declarado e a assinatura do conteudo

(deftest tipo-declarado-vazio-ou-octet-stream-vale-e-a-assinatura-confere
  (let [c (cenario) ente (:ente c) eu (random-uuid) svc (servico c #{}) id (protocolar! c :esic eu)
        zip "PK\u0003\u0004 conteudo de um documento zip"]
    (testing ".odt declarado octet-stream (navegador que nao conhece o formato): aceito, e o tipo gravado e' o canonico"
      (let [r (anexar-meu svc ente eu "esic" id "oficio.odt" "application/octet-stream" (str zip " odt"))]
        (is (= 201 (:status r)))
        (is (= "application/vnd.oasis.opendocument.text" (get-in r [:corpo :tipo-midia])))))
    (testing "tipo declarado AUSENTE (a parte sem Content-Type): aceito pela extensao"
      (let [r (anexar-meu svc ente eu "esic" id "oficio.docx" nil (str zip " docx"))]
        (is (= 201 (:status r)))
        (is (= "application/vnd.openxmlformats-officedocument.wordprocessingml.document" (get-in r [:corpo :tipo-midia])))))
    (testing "um .exe renomeado para .pdf (mesmo declarado application/pdf): 415 pelo CONTEUDO, e nenhum blob a mais"
      (let [antes (count @(:m c))
            r (anexar-meu svc ente eu "esic" id "recibo.pdf" "application/pdf" "MZ\u0090 this program cannot be run in DOS mode")]
        (is (= 415 (:status r)))
        (is (re-find #"(?i)conte" (get-in r [:corpo :erro])) "a mensagem diz que e' o conteudo que nao confere")
        (is (= antes (count @(:m c))))))
    (testing "o mesmo, declarado octet-stream: 415"
      (is (= 415 (:status (anexar-meu svc ente eu "esic" id "recibo2.pdf" "application/octet-stream" "MZ\u0090 programa")))))
    (testing ".png com conteudo de PDF, .docx com conteudo de texto: 415"
      (is (= 415 (:status (anexar-meu svc ente eu "esic" id "foto.png" "image/png" pdf))))
      (is (= 415 (:status (anexar-meu svc ente eu "esic" id "oficio2.docx" nil "isto nao e um zip")))))
    (testing ".txt com byte NUL: 415; sem NUL: 201"
      (is (= 415 (:status (anexar-meu svc ente eu "esic" id "nota.txt" "text/plain" "ola\u0000mundo"))))
      (is (= 201 (:status (anexar-meu svc ente eu "esic" id "nota2.txt" "text/plain" "ola mundo")))))
    (testing "a regra e' a mesma na rota do balcao"
      (let [c2 (cenario) sec (random-uuid) id2 (protocolar! c2 :esic (random-uuid))
            svc2 (servico c2 #{"secretario"})]
        ((get-in especies [:esic :responder!]) c2 sec id2)
        (is (= 415 (:status (anexar-da-casa svc2 (:ente c2) sec "esic" id2 "falso.pdf" "application/pdf" "MZ\u0090 programa"))))
        (is (= 201 (:status (anexar-da-casa svc2 (:ente c2) sec "esic" id2 "oficio.odt" "application/octet-stream" zip))))))))

;; ---------------------------------------------------------------- B7: o nome no banco

(deftest o-banco-barra-aspas-e-controle-no-nome
  (let [c (cenario) ente (:ente c) id (protocolar! c :esic (random-uuid))
        base {:objeto-tipo "pedido_esic" :objeto-id id :origem "casa" :tipo-midia "application/pdf" :bytes 10
              :enviado-por (random-uuid)}
        ;; um sha256 DISTINTO por nome (o mesmo sha no mesmo protocolo seria reenvio idempotente, nao uma tentativa de gravar)
        sha-de (fn [nome] (let [h (format "%x" (bit-and (hash nome) 0xffffff))] (str (apply str (repeat (- 64 (count h)) "a")) h)))
        gravar (fn [nome] (let [aid (random-uuid)]
                            (try (repo-part/anexar-ao-atendimento! *repo* ente
                                   (assoc base :id aid :nome nome :sha256 (sha-de nome)
                                               :chave-objeto (str "atendimento/" ente "/" id "/" aid)) 5)
                                 :gravou
                                 (catch Exception e (class e)))))]
    (is (= :gravou (gravar "normal.pdf")))
    (is (= org.postgresql.util.PSQLException (gravar "com\"aspas.pdf")) "aspas")
    (is (= org.postgresql.util.PSQLException (gravar "com\u0007controle.pdf")) "controle C0")
    (is (= org.postgresql.util.PSQLException (gravar "com\u0085nel.pdf")) "controle C1")
    (is (= org.postgresql.util.PSQLException (gravar "com\tTab.pdf")) "tab")))

(deftest o-nome-que-chega-pela-borda-sai-limpo
  (let [c (cenario) ente (:ente c) eu (random-uuid) svc (servico c #{}) id (protocolar! c :esic eu)
        r (anexar-meu svc ente eu "esic" id "recibo‮fdp.pdf" "application/pdf" pdf)]
    (is (= 201 (:status r)))
    (is (= "recibofdp.pdf" (get-in r [:corpo :nome])) "U+202E (inversor de direcao) nao chega a ninguem")
    (testing "corte em 200 preserva a extensao"
      (let [r2 (anexar-meu svc ente eu "esic" id (str (apply str (repeat 300 "b")) ".pdf") "application/pdf" (str pdf " outro"))]
        (is (= 201 (:status r2)))
        (is (= 200 (count (get-in r2 [:corpo :nome]))))
        (is (str/ends-with? (get-in r2 [:corpo :nome]) ".pdf"))))))

;; ---------------------------------------------------------------- B8: reenvio idempotente

(deftest reenviar-o-mesmo-arquivo-devolve-o-que-ja-existe
  (let [c (cenario) ente (:ente c) eu (random-uuid) sec (random-uuid) svc (servico c #{}) sec-svc (servico c #{"secretario"})
        id (protocolar! c :esic eu)
        r1 (anexar-meu svc ente eu "esic" id "contrato.pdf" "application/pdf" pdf)
        r2 (anexar-meu svc ente eu "esic" id "contrato-de-novo.pdf" "application/pdf" pdf)]
    (is (= 201 (:status r1)))
    (is (= 201 (:status r2)) "a mesma forma de resposta")
    (is (= (get-in r1 [:corpo :id]) (get-in r2 [:corpo :id])) "o mesmo anexo, nao um novo")
    (is (= "contrato.pdf" (get-in r2 [:corpo :nome])) "o nome e' o da primeira vez")
    (is (= 1 (count (linhas-do-banco ente "SELECT 1 FROM participacao.anexo"))) "sem linha nova")
    (is (= 1 (count @(:m c))) "sem blob novo")
    (testing "no limite de 5 a conferencia PREVIA recusa (409) antes de ler o corpo: so' ao ler da' para saber que e' reenvio"
      (dotimes [i 4] (anexar-meu svc ente eu "esic" id (str "m" i ".txt") "text/plain" (str "x" i)))
      (is (= 409 (:status (anexar-meu svc ente eu "esic" id "novo.txt" "text/plain" "algo novo"))))
      (is (= 409 (:status (anexar-meu svc ente eu "esic" id "contrato.pdf" "application/pdf" pdf))))
      (is (= 5 (count @(:m c))) "e nao subiu blob nenhum")
      (is (= 5 (count (linhas-do-banco ente "SELECT 1 FROM participacao.anexo")))))
    (testing "o mesmo conteudo em OUTRO protocolo, ou em outra origem, e' outro anexo"
      (let [id2 (protocolar! c :esic eu)]
        (is (not= (get-in r1 [:corpo :id]) (get-in (anexar-meu svc ente eu "esic" id2 "contrato.pdf" "application/pdf" pdf) [:corpo :id]))))
      ((get-in especies [:esic :responder!]) c sec id)
      (let [da-casa (anexar-da-casa sec-svc ente sec "esic" id "contrato.pdf" "application/pdf" pdf)]
        (is (= 201 (:status da-casa)))
        (is (not= (get-in r1 [:corpo :id]) (get-in da-casa [:corpo :id])))))
    (testing "depois de RETIRADO, o mesmo arquivo volta a ser aceito como novo"
      (retirar sec-svc ente sec "esic" id (get-in r1 [:corpo :id]) (motivo "retirado para teste"))
      (let [r3 (anexar-meu svc ente eu "esic" id "contrato.pdf" "application/pdf" pdf)]
        (is (= 201 (:status r3)))
        (is (not= (get-in r1 [:corpo :id]) (get-in r3 [:corpo :id])))))))

;; ---------------------------------------------------------------- B9: linha sem blob

(defn- repo-que-confirma-e-depois-falha
  "Um Repo cujo `anexar-ao-atendimento!` GRAVA de verdade e depois lanca: o resultado e' DESCONHECIDO para quem chamou
  (o commit passou, a resposta se perdeu). `reify` e nao `with-redefs`: o compilador chama o metodo do record direto."
  [real]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-part/RepoParticipacao
    (atendimento-esic [_ ente id] (repo-part/atendimento-esic real ente id))
    (anexar-ao-atendimento! [_ ente m limite]
      (repo-part/anexar-ao-atendimento! real ente m limite)
      (throw (RuntimeException. "a conexao caiu depois do commit")))))

(deftest resultado-desconhecido-nao-remove-o-blob
  ;; B9: so' remove o blob quando a linha foi COMPROVADAMENTE recusada antes do commit. Se o commit passou e algo lancou
  ;; depois, a linha existe e aponta para o blob: removê-lo deixaria o download quebrado.
  (let [c (cenario) ente (:ente c) sec (random-uuid) id (protocolar! c :esic (random-uuid))]
    ((get-in especies [:esic :responder!]) c sec id)
    (let [r (try (controllers/anexar-ao-atendimento! (repo-que-confirma-e-depois-falha *repo*) (:store c) (:relogio c)
                                                     (servidor ente sec) :esic id
                                                     {:nome "a.txt" :tipo-midia "text/plain" :conteudo (bytes-de "conteudo")})
                 (catch RuntimeException e (ex-message e)))
          [linha] (linhas-do-banco ente "SELECT chave_objeto FROM participacao.anexo")]
      (is (= "a conexao caiu depois do commit" r))
      (is (some? linha) "a linha foi gravada")
      (is (contains? @(:m c) (:chave_objeto linha)) "e o blob a que ela aponta continua la'"))))

;; ---------------------------------------------------------------- B12: a secretaria que baixa fica na trilha

(deftest download-pela-secretaria-e-leitura-sensivel-na-trilha
  (let [c (cenario) ente (:ente c) eu (random-uuid) sec (random-uuid) cid (servico c #{})
        id (protocolar! c :esic eu)
        aid (get-in (anexar-meu cid ente eu "esic" id "meu.pdf" "application/pdf" pdf) [:corpo :id])
        req-balcao {:request-method :get :path-params {:id (str id) :anexo aid} :headers (como ente sec)}
        resp (executar-cadeia c #{"secretario"} :participacao/baixar-anexo-esic req-balcao)
        ator (servidor ente sec)]
    (is (= 200 (:status resp)))
    (testing "a resposta marca a leitura como sensivel"
      (is (= "leitura_sensivel" (get-in resp [:auditoria :classe])))
      (is (= id (parse-uuid (get-in resp [:auditoria :recurso-id])))))
    (testing "e a trilha a REGISTRA (a leitura comum nao entra; esta entra)"
      (let [reg (auditoria/registro-da-requisicao (assoc req-balcao :ator ator) resp :participacao/baixar-anexo-esic)]
        (is (some? reg))
        (is (= "leitura_sensivel" (:classe reg)))
        (is (= ente (:ente-id reg)))))
    (testing "o download do PROPRIO requerente nao e' leitura sensivel (ele le o que e' dele)"
      (let [r (executar-cadeia c #{} :participacao/baixar-meu-anexo-esic
                               {:request-method :get :path-params {:id (str id) :anexo aid} :headers (como ente eu)})]
        (is (= 200 (:status r)))
        (is (nil? (:auditoria r)))
        (is (nil? (auditoria/registro-da-requisicao (assoc req-balcao :ator (cidadao ente eu)) r :participacao/baixar-meu-anexo-esic)))))))
