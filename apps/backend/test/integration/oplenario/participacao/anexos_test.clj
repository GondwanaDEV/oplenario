(ns oplenario.participacao.anexos-test
  "INTEGRACAO (PG real + borda HTTP) — os ANEXOS da resposta (e-SIC, ouvidoria, LGPD). A resposta a um pedido costuma SER um
  documento: a secretaria anexa arquivos nos 10 minutos seguintes ao ultimo ato de resposta (ate' 5, 10 MB, 9 tipos) e o
  requerente os baixa em /meus-protocolos. Prova: o upload feliz por especie (blob no store, sha256 gravado, nada de
  chave/sha/quem-enviou no fio); as recusas (409 sem resposta, fora da janela e no 6o; 413; 415; papel; outra Casa); o
  blob que NAO fica orfao quando a linha e' recusada — inclusive numa corrida real de threads; o download (secretaria,
  dono, e o 404 uniforme de quem nao e' o dono); as rotas PUBLICAS de acompanhamento sem anexo; e o banco (RLS,
  append-only, CHECKs, o inventario da exportacao/apagamento da Casa)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [oplenario.config :as config]
            [oplenario.encerramento.arquivos :as arquivos]
            [oplenario.encerramento.inventario :as inventario]
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
              ;; como o S3: nao recursivo devolve as subpastas (terminadas em `/`); recursivo, todos os blobs abaixo
              (listar [_ prefixo recursivo?]
                (->> (keys @m)
                     (filter #(str/starts-with? % prefixo))
                     (map (fn [k] (let [resto (subs k (count prefixo))]
                                    (if (and (not recursivo?) (str/includes? resto "/"))
                                      (str prefixo (first (str/split resto #"/")) "/")
                                      k))))
                     distinct sort vec)))}))

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
  "O servico da borda para quem tem `papeis` (o fake devolve os mesmos papeis a qualquer identidade)."
  [c papeis]
  (let [auth (it/autenticacao (idp-dev/idp-dev) (fake-repo-identidade papeis))]
    (-> (http/servico (config/carregar)
                      (participacao-http/rotas {:auth auth :repo-participacao *repo* :relogio (:relogio c)
                                                :objeto-store (:store c)
                                                :resolver-ente-publico participacao-http/resolver-ente-publico-uuid})
                      it/globais)
        ph/create-server ::ph/service-fn)))

(defn- como [ente iid]
  {"authorization" (str "Bearer " (json/write-value-as-string {:sub "u" :ente-id (str ente) :identidade-id (str iid)}))})

(defn- ler [r] (when (seq (:body r)) (json/read-value (:body r) json/keyword-keys-object-mapper)))

(defn- multipart [nome tipo conteudo]
  (let [fronteira "----oplenario-teste-anexo"]
    {:ct (str "multipart/form-data; boundary=" fronteira)
     :body (str "--" fronteira "\r\n"
                "Content-Disposition: form-data; name=\"arquivo\"; filename=\"" nome "\"\r\n"
                "Content-Type: " tipo "\r\n\r\n"
                conteudo "\r\n"
                "--" fronteira "--\r\n")}))

(defn- anexar
  "POST /atendimento/<rota>/:id/anexos. `svc` ja' escolhe os papeis."
  [svc ente quem rota id nome tipo conteudo]
  (let [{:keys [ct body]} (multipart nome tipo conteudo)
        r (pt/response-for svc :post (str "/atendimento/" rota "/" id "/anexos")
                           :headers (assoc (como ente quem) "Content-Type" ct) :body body)]
    {:status (:status r) :corpo (ler r) :bruto (:body r)}))

(defn- sha256 [^String s] (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))))

;; as tres especies: como protocolar e como responder (cada uma pelo seu controller)
(def ^:private especies
  {:esic {:rota "esic" :tipo-banco "pedido_esic"
          :protocolar (fn [c eu] (:id (controllers/protocolar-pedido *repo* (:relogio c) (cidadao (:ente c) eu)
                                                                     {:assunto "Folha" :descricao "Quero a folha."})))
          :responder! (fn [c sec id] (controllers/responder-pedido! *repo* (:relogio c) (servidor (:ente c) sec) id {:corpo "Segue em anexo."}))}
   :ouvidoria {:rota "ouvidoria" :tipo-banco "manifestacao_ouvidoria"
               :protocolar (fn [c eu] (:id (controllers/protocolar-manifestacao! *repo* (:relogio c) (cidadao (:ente c) eu)
                                                                                 {:tipo "reclamacao" :assunto "Fila" :descricao "Demora." :anonima false})))
               :responder! (fn [c sec id] (controllers/responder-manifestacao! *repo* (:relogio c) (servidor (:ente c) sec) id {:corpo "Segue em anexo."}))}
   :lgpd {:rota "lgpd" :tipo-banco "solicitacao_titular"
          :protocolar (fn [c eu] (:id (controllers/solicitar-titular! *repo* (:relogio c) (cidadao (:ente c) eu)
                                                                      {:tipo "acessar" :detalhe "Meus dados."})))
          :responder! (fn [c sec id] (controllers/responder-solicitacao! *repo* (:relogio c) (servidor (:ente c) sec) id {:corpo "Segue em anexo."}))}})

(defn- protocolo-respondido!
  "Protocola (como `eu`) e responde (como `sec`) um protocolo da `especie`. Devolve o id."
  [c especie eu sec]
  (let [{:keys [protocolar responder!]} (especies especie)
        id (protocolar c eu)]
    (responder! c sec id)
    id))

(defn- linhas-do-banco [ente sql & params]
  (tenancy/com-tenant* (:ds *c*) ente #(jdbc/execute! % (into [sql] params) {:builder-fn rs/as-unqualified-maps})))

(def ^:private conteudo-pdf "%PDF-1.4 planilha da folha de pagamento de 2025")

;; ---------------------------------------------------------------- o upload feliz, por especie

(deftest upload-feliz-nas-tres-especies
  (doseq [especie [:esic :ouvidoria :lgpd]]
    (testing (name especie)
      (let [c (cenario) ente (:ente c) eu (random-uuid) sec (random-uuid)
            {:keys [rota tipo-banco]} (especies especie)
            id (protocolo-respondido! c especie eu sec)
            svc (servico c #{"secretario"})
            r (anexar svc ente sec rota id "folha 2025.pdf" "application/pdf" conteudo-pdf)]
        (is (= 201 (:status r)))
        (testing "o 201 so' diz o que a tela precisa: nada de chave, sha256 nem quem enviou"
          (is (= #{:id :nome :tipo-midia :bytes :origem :enviado-em} (set (keys (:corpo r)))))
          (is (= {:nome "folha 2025.pdf" :tipo-midia "application/pdf" :bytes (count (.getBytes ^String conteudo-pdf "UTF-8"))
                  :origem "casa"}
                 (select-keys (:corpo r) [:nome :tipo-midia :bytes :origem])))
          (is (not (str/includes? (:bruto r) (str sec))) "quem enviou nao sai")
          (is (not (str/includes? (:bruto r) "atendimento/")) "a chave do object storage nao sai"))
        (testing "o blob esta no store, na chave da convencao, e o sha256 gravado e' o dos bytes"
          (let [anexo-id (get-in r [:corpo :id])
                chave (str "atendimento/" ente "/" id "/" anexo-id)
                [linha] (linhas-do-banco ente "SELECT * FROM participacao.anexo WHERE id = ?::uuid" anexo-id)]
            (is (= conteudo-pdf (String. ^bytes (get @(:m c) chave) "UTF-8")))
            (is (= (sha256 conteudo-pdf) (:sha256 linha)))
            (is (= chave (:chave_objeto linha)))
            (is (= [tipo-banco id "casa" sec] [(:objeto_tipo linha) (:objeto_id linha) (:origem linha) (:enviado_por linha)])
                "o protocolo, a origem `casa` (a unica desta fatia) e quem enviou, vindo do ATOR")))))))

(deftest o-tipo-gravado-e-o-canonico-da-extensao
  (let [c (cenario) ente (:ente c) sec (random-uuid) id (protocolo-respondido! c :esic (random-uuid) sec)
        svc (servico c #{"secretario"})
        r (anexar svc ente sec "esic" id "exportado.csv" "application/vnd.ms-excel" "a,b\n1,2")]
    (is (= 201 (:status r)))
    (is (= "text/csv" (get-in r [:corpo :tipo-midia])) "o navegador no Windows declara vnd.ms-excel; grava e serve text/csv")))

;; ---------------------------------------------------------------- o detalhe do balcao

(deftest o-detalhe-lista-os-anexos-e-diz-quando-cabe-anexar
  (let [c (cenario) ente (:ente c) sec (random-uuid) eu (random-uuid) svc (servico c #{"secretario"})
        id (protocolo-respondido! c :esic eu sec)
        detalhe #(ler (pt/response-for svc :get (str "/atendimento/esic/" id) :headers (como ente sec)))]
    (testing "recem respondido, sem anexo: cabe anexar"
      (let [d (detalhe)]
        (is (true? (get-in d [:acoes :pode-anexar])))
        (is (= [] (:anexos d)))))
    (testing "com anexos: a lista, sem chave/sha/quem enviou; ao chegar em 5 deixa de caber"
      (dotimes [i 5] (is (= 201 (:status (anexar svc ente sec "esic" id (str "a" i ".txt") "text/plain" (str "conteudo " i))))))
      (let [d (detalhe)]
        (is (= 5 (count (:anexos d))))
        (is (= ["a0.txt" "a1.txt" "a2.txt" "a3.txt" "a4.txt"] (map :nome (:anexos d))) "na ordem em que chegaram")
        (is (every? #(= #{:id :nome :tipo-midia :bytes :origem :enviado-em} (set (keys %))) (:anexos d)))
        (is (false? (get-in d [:acoes :pode-anexar])) "5 anexos da Casa: o limite")))
    (testing "passada a janela de 10 minutos, tambem deixa de caber (outro protocolo, sem anexos)"
      (let [id2 (protocolo-respondido! c :esic eu sec)]
        (is (true? (get-in (ler (pt/response-for svc :get (str "/atendimento/esic/" id2) :headers (como ente sec)))
                           [:acoes :pode-anexar])))
        (avancar! c 601)
        (is (false? (get-in (ler (pt/response-for svc :get (str "/atendimento/esic/" id2) :headers (como ente sec)))
                            [:acoes :pode-anexar])))))))

;; ---------------------------------------------------------------- as recusas

(deftest sem-resposta-409
  (doseq [especie [:esic :ouvidoria :lgpd]]
    (let [c (cenario) ente (:ente c) sec (random-uuid) svc (servico c #{"secretario"})
          {:keys [rota protocolar]} (especies especie)
          id (protocolar c (random-uuid))
          r (anexar svc ente sec rota id "a.pdf" "application/pdf" conteudo-pdf)]
      (is (= 409 (:status r)) (name especie))
      (is (re-find #"ainda não tem resposta" (get-in r [:corpo :erro])))
      (is (empty? @(:m c)) "e nada foi para o store"))))

(deftest ouvidoria-arquivada-nao-tem-resposta-a-anexar
  (let [c (cenario) ente (:ente c) sec (random-uuid) eu (random-uuid) svc (servico c #{"secretario"})
        m (controllers/protocolar-manifestacao! *repo* (:relogio c) (cidadao ente eu)
                                                {:tipo "denuncia" :assunto "Obra" :descricao "Parada." :anonima false})]
    (controllers/arquivar-manifestacao! *repo* (:relogio c) (servidor ente sec) (:id m) {:motivo "Fora da competencia."})
    (let [r (anexar svc ente sec "ouvidoria" (:id m) "a.pdf" "application/pdf" conteudo-pdf)]
      (is (= 409 (:status r)) "arquivar e' encerrar SEM resposta de merito: nao ha documento a entregar")
      (is (re-find #"ainda não tem resposta" (get-in r [:corpo :erro]))))))

(deftest fora-da-janela-409
  (let [c (cenario) ente (:ente c) sec (random-uuid) svc (servico c #{"secretario"})
        id (protocolo-respondido! c :esic (random-uuid) sec)]
    (avancar! c 600)
    (is (= 201 (:status (anexar svc ente sec "esic" id "no-limite.pdf" "application/pdf" conteudo-pdf))) "o minuto 10 ainda vale")
    (avancar! c 1)
    (let [r (anexar svc ente sec "esic" id "tarde.pdf" "application/pdf" conteudo-pdf)]
      (is (= 409 (:status r)))
      (is (re-find #"10 minutos" (get-in r [:corpo :erro]))))
    (is (= 1 (count @(:m c))) "o recusado nao deixou blob")))

(deftest a-decisao-do-recurso-abre-uma-nova-janela
  (let [c (cenario) ente (:ente c) sec (random-uuid) eu (random-uuid) svc (servico c #{"secretario"})
        id (protocolo-respondido! c :esic eu sec)
        rec (controllers/interpor-recurso! *repo* (:relogio c) (cidadao ente eu) id {:motivo "Faltou o valor."})]
    (avancar! c 3600)
    (is (= 409 (:status (anexar svc ente sec "esic" id "tarde.pdf" "application/pdf" conteudo-pdf))) "a janela da resposta passou")
    (controllers/decidir-recurso! *repo* (:relogio c) (servidor ente sec) (:id rec) {:corpo "Provido: segue o documento."})
    (is (= 201 (:status (anexar svc ente sec "esic" id "decisao.pdf" "application/pdf" conteudo-pdf)))
        "no e-SIC a decisao do recurso tambem e' ato de resposta: abre a janela de novo")))

(deftest o-sexto-anexo-409-sem-blob-orfao
  (let [c (cenario) ente (:ente c) sec (random-uuid) svc (servico c #{"secretario"})
        id (protocolo-respondido! c :lgpd (random-uuid) sec)]
    (dotimes [i 5] (is (= 201 (:status (anexar svc ente sec "lgpd" id (str "a" i ".txt") "text/plain" "x")))))
    (let [r (anexar svc ente sec "lgpd" id "sexto.txt" "text/plain" "x")]
      (is (= 409 (:status r)))
      (is (re-find #"5 anexos" (get-in r [:corpo :erro]))))
    (is (= 5 (count @(:m c))) "5 blobs, nao 6")))

(deftest tamanho-413-e-corpo-sem-arquivo-400
  (let [c (cenario) ente (:ente c) sec (random-uuid) svc (servico c #{"secretario"})
        id (protocolo-respondido! c :esic (random-uuid) sec)]
    (is (= 413 (:status (anexar svc ente sec "esic" id "grande.txt" "text/plain" (apply str (repeat (inc (* 10 1024 1024)) "a"))))))
    (is (= 400 (:status (anexar svc ente sec "esic" id "vazio.txt" "text/plain" ""))))
    (is (= 400 (:status (pt/response-for svc :post (str "/atendimento/esic/" id "/anexos")
                                         :headers (assoc (como ente sec) "Content-Type" "application/json") :body "{}")))
        "sem multipart: nada a anexar")
    (is (empty? @(:m c)))))

(deftest tipo-nao-aceito-415
  (let [c (cenario) ente (:ente c) sec (random-uuid) svc (servico c #{"secretario"})
        id (protocolo-respondido! c :esic (random-uuid) sec)]
    (doseq [[nome tipo] [["programa.exe" "application/pdf"]            ; extensao fora da lista, tipo de documento
                         ["pagina.html" "text/html"]
                         ["planilha.xls" "application/vnd.ms-excel"]
                         ["folha.pdf" "text/plain"]                    ; extensao na lista, tipo de OUTRA coisa
                         ["foto.png" "image/jpeg"]
                         ["folha.pdf" "application/octet-stream"]
                         ["semextensao" "application/pdf"]]]
      (let [r (anexar svc ente sec "esic" id nome tipo "conteudo")]
        (is (= 415 (:status r)) (str nome " " tipo))
        (is (re-find #"Aceitamos PDF, PNG, JPEG, TXT, CSV, DOCX, XLSX, ODT e ODS" (get-in r [:corpo :erro])))))
    (is (empty? @(:m c)) "nenhum blob dos recusados")
    (is (= [] (linhas-do-banco ente "SELECT id FROM participacao.anexo")))))

(deftest papel-errado-e-sem-token
  (let [c (cenario) ente (:ente c) sec (random-uuid) eu (random-uuid)
        id (protocolo-respondido! c :esic eu sec)
        ok (anexar (servico c #{"secretario"}) ente sec "esic" id "a.pdf" "application/pdf" conteudo-pdf)
        anexo-id (get-in ok [:corpo :id])]
    (is (= 201 (:status ok)))
    (doseq [papeis [#{} #{"vereador"} #{"juridico"}]]
      (let [svc (servico c papeis)]
        (is (= 403 (:status (anexar svc ente eu "esic" id "b.pdf" "application/pdf" conteudo-pdf))) (str "POST " papeis))
        (is (= 403 (:status (pt/response-for svc :get (str "/atendimento/esic/" id "/anexos/" anexo-id) :headers (como ente eu))))
            (str "GET " papeis))))
    (is (= 1 (count @(:m c))) "o 403 nao leu o corpo nem subiu blob")
    (let [svc (servico c #{"secretario"})
          {:keys [ct body]} (multipart "a.pdf" "application/pdf" conteudo-pdf)]
      (is (= 401 (:status (pt/response-for svc :post (str "/atendimento/esic/" id "/anexos")
                                           :headers {"Content-Type" ct} :body body))))
      (is (= 401 (:status (pt/response-for svc :get (str "/atendimento/esic/" id "/anexos/" anexo-id))))))))

(deftest outra-casa-404
  (let [c (cenario) ente (:ente c) sec (random-uuid) svc (servico c #{"secretario"})
        id (protocolo-respondido! c :esic (random-uuid) sec)
        ok (anexar svc ente sec "esic" id "a.pdf" "application/pdf" conteudo-pdf)
        outra (random-uuid)]
    (is (= 404 (:status (anexar svc outra sec "esic" id "b.pdf" "application/pdf" conteudo-pdf))) "upload no protocolo de outra Casa")
    (is (= 404 (:status (pt/response-for svc :get (str "/atendimento/esic/" id "/anexos/" (get-in ok [:corpo :id]))
                                         :headers (como outra sec)))) "download do anexo de outra Casa")
    (is (= 404 (:status (anexar svc ente sec "esic" (random-uuid) "b.pdf" "application/pdf" conteudo-pdf))) "protocolo inexistente")
    (is (= 1 (count @(:m c))))))

;; ---------------------------------------------------------------- o blob nao fica orfao

(defn- repo-que-confere-antes-de-todos-escreverem
  "O Repo de verdade, mas com a CONFERENCIA PREVIA do controller (`atendimento-esic`) enganada: le o protocolo e SO'
  DEPOIS espera `barreira` (ou nada, se nil) — a janela em que dois envios concorrentes leem o mesmo numero de anexos e os
  dois passam da conferencia. Quem decide o limite de verdade, a essa altura, e' o Repo (trava + contagem na tx). Um
  `reify` e nao `with-redefs`: o compilador chama o metodo do record direto, sem passar pelo var do protocolo."
  [real ^java.util.concurrent.CyclicBarrier barreira zerar-anexos?]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-part/RepoParticipacao
    (atendimento-esic [_ ente id]
      (let [d (repo-part/atendimento-esic real ente id)]
        (when barreira (.await barreira 10 java.util.concurrent.TimeUnit/SECONDS))
        (cond-> d zerar-anexos? (assoc :anexos []))))
    (anexar-ao-atendimento! [_ ente m limite] (repo-part/anexar-ao-atendimento! real ente m limite))))

(defn- anexar-direto
  "O controller sem HTTP: devolve o anexo, ou o `ex-data` da recusa."
  [repo c sec id nome conteudo]
  (try (controllers/anexar-ao-atendimento! repo (:store c) (:relogio c) (servidor (:ente c) sec) :esic id
                                           {:nome nome :tipo-midia "text/plain" :conteudo (.getBytes ^String conteudo "UTF-8")})
       (catch clojure.lang.ExceptionInfo e (assoc (ex-data e) :recusado true))))

(deftest linha-recusada-nao-deixa-blob-orfao
  ;; O controller confere o limite ANTES (com o que leu) e o Repo confere DE NOVO dentro da tx. Se o primeiro passa e o
  ;; segundo recusa (um envio concorrente), o blob que ja' subiu sai. Aqui a conferencia previa le ZERO anexos para
  ;; exercitar so' o caminho do Repo.
  (let [c (cenario) ente (:ente c) sec (random-uuid) svc (servico c #{"secretario"})
        id (protocolo-respondido! c :esic (random-uuid) sec)]
    (dotimes [i 5] (is (= 201 (:status (anexar svc ente sec "esic" id (str "a" i ".txt") "text/plain" "x")))))
    (let [antes (set (keys @(:m c)))
          r (anexar-direto (repo-que-confere-antes-de-todos-escreverem *repo* nil true) c sec id "corrida.txt" "y")]
      (is (= :conflito/anexos-demais (:tipo r)) "o Repo recusou o 6o, que passou da conferencia previa")
      (is (= antes (set (keys @(:m c)))) "o blob que subiu para a linha recusada saiu: nenhum orfao")
      (is (= 5 (count (linhas-do-banco ente "SELECT id FROM participacao.anexo")))))))

(deftest corrida-de-threads-no-limite-de-5
  ;; 4 anexos; 3 envios AO MESMO TEMPO. A barreira garante que os tres leram 4 na conferencia previa ANTES de qualquer um
  ;; escrever; a trava consultiva do Repo deixa passar exatamente UM; os outros 2 recebem o conflito e seus blobs saem.
  ;; No fim: 5 linhas, 5 blobs, nenhum orfao.
  (let [c (cenario) ente (:ente c) sec (random-uuid) svc (servico c #{"secretario"})
        id (protocolo-respondido! c :esic (random-uuid) sec)]
    (dotimes [i 4] (is (= 201 (:status (anexar svc ente sec "esic" id (str "a" i ".txt") "text/plain" "x")))))
    (let [barreira (java.util.concurrent.CyclicBarrier. 3)
          repo (repo-que-confere-antes-de-todos-escreverem *repo* barreira false)
          rs (->> (range 3)
                  (mapv (fn [i] (future (anexar-direto repo c sec id (str "corrida" i ".txt") (str "z" i)))))
                  (mapv deref))]
      (is (= 1 (count (remove :recusado rs))) "exatamente um passou")
      (is (= [:conflito/anexos-demais :conflito/anexos-demais] (map :tipo (filter :recusado rs))))
      (is (= 5 (count (linhas-do-banco ente "SELECT id FROM participacao.anexo"))))
      (is (= 5 (count @(:m c))) "5 blobs: os dois que perderam a corrida foram removidos"))))

;; ---------------------------------------------------------------- download

(deftest download-secretaria-e-dono-com-cabecalhos-de-arquivo
  (doseq [especie [:esic :ouvidoria :lgpd]]
    (testing (name especie)
      (let [c (cenario) ente (:ente c) eu (random-uuid) sec (random-uuid) outra-pessoa (random-uuid)
            {:keys [rota]} (especies especie)
            id (protocolo-respondido! c especie eu sec)
            sec-svc (servico c #{"secretario"}) cid-svc (servico c #{})
            conteudo "ano;valor\n2025;10"
            anexo-id (get-in (anexar sec-svc ente sec rota id "dados do ano.csv" "text/csv" conteudo) [:corpo :id])
            balcao (str "/atendimento/" rota "/" id "/anexos/" anexo-id)
            meu (str "/portal/meus-protocolos/" rota "/" id "/anexos/" anexo-id)
            confere (fn [r]
                      (is (= 200 (:status r)))
                      (is (= conteudo (:body r)))
                      (is (= "text/csv" (get-in r [:headers "Content-Type"])))
                      (is (str/starts-with? (get-in r [:headers "Content-Disposition"]) "attachment; filename=\"dados do ano.csv\"")
                          "SEMPRE como arquivo, nunca inline")
                      (is (= "nosniff" (get-in r [:headers "X-Content-Type-Options"])))
                      (is (= (str (count conteudo)) (str (get-in r [:headers "Content-Length"])))))]
        (testing "a secretaria da Casa baixa"
          (confere (pt/response-for sec-svc :get balcao :headers (como ente sec))))
        (testing "o DONO do protocolo baixa pela rota dele"
          (confere (pt/response-for cid-svc :get meu :headers (como ente eu))))
        (testing "outro cidadao: 404 (o mesmo de 'nao existe')"
          (is (= 404 (:status (pt/response-for cid-svc :get meu :headers (como ente outra-pessoa)))))
          (is (= 404 (:status (pt/response-for cid-svc :get (str "/portal/meus-protocolos/" rota "/" id "/anexos/" (random-uuid))
                                               :headers (como ente eu))))
              "anexo que nao existe: o mesmo 404"))
        (testing "o dono numa OUTRA Casa: 404"
          (is (= 404 (:status (pt/response-for cid-svc :get meu :headers (como (random-uuid) eu))))))
        (testing "anonimo: negado"
          (is (= 401 (:status (pt/response-for cid-svc :get meu))))
          (is (= 401 (:status (pt/response-for sec-svc :get balcao)))))
        (testing "o anexo e' amarrado ao protocolo da URL: o id certo no protocolo errado nao baixa"
          (let [outro-id (protocolo-respondido! c especie eu sec)]
            (is (= 404 (:status (pt/response-for sec-svc :get (str "/atendimento/" rota "/" outro-id "/anexos/" anexo-id)
                                                 :headers (como ente sec)))))
            (is (= 404 (:status (pt/response-for cid-svc :get (str "/portal/meus-protocolos/" rota "/" outro-id "/anexos/" anexo-id)
                                                 :headers (como ente eu)))))))))))

(deftest o-nome-do-arquivo-nao-escapa-do-cabecalho
  ;; ASCII de proposito: o parser multipart do Ring (commons-fileupload) transforma o nome do arquivo em Path, e numa JVM
  ;; sem locale UTF-8 (o container `clojure:*` de teste usa POSIX) um nome acentuado vira 400. A imagem de producao
  ;; (eclipse-temurin, LANG=en_US.UTF-8) e o CI nao tem isso; o cabecalho com acento e' coberto no unit de
  ;; `kernel/arquivo/content-disposition`.
  (let [c (cenario) ente (:ente c) sec (random-uuid) svc (servico c #{"secretario"})
        id (protocolo-respondido! c :esic (random-uuid) sec)
        r (anexar svc ente sec "esic" id "../../etc/relatorio final.txt" "text/plain" "x")
        d (pt/response-for svc :get (str "/atendimento/esic/" id "/anexos/" (get-in r [:corpo :id])) :headers (como ente sec))]
    (is (= 201 (:status r)) (str (:bruto r)))
    (is (= "relatorio final.txt" (get-in r [:corpo :nome])) "so' o ultimo segmento do caminho")
    (is (= "attachment; filename=\"relatorio final.txt\"; filename*=UTF-8''relatorio%20final.txt"
           (get-in d [:headers "Content-Disposition"])))))

;; ---------------------------------------------------------------- o requerente ve a lista; o publico nao

(deftest meus-protocolos-lista-os-anexos-do-dono-e-so-dele
  (let [c (cenario) ente (:ente c) eu (random-uuid) intruso (random-uuid) sec (random-uuid)
        sec-svc (servico c #{"secretario"}) cid-svc (servico c #{})
        ids (into {} (map (fn [e] [e (protocolo-respondido! c e eu sec)])) [:esic :ouvidoria :lgpd])]
    (doseq [[e id] ids] (is (= 201 (:status (anexar sec-svc ente sec (get-in especies [e :rota]) id (str (name e) ".pdf")
                                                    "application/pdf" conteudo-pdf)))))
    (let [r (pt/response-for cid-svc :get "/portal/meus-protocolos" :headers (como ente eu))
          w (ler r)]
      (is (= 200 (:status r)))
      (doseq [[chave nome] [[:pedidos-esic "esic.pdf"] [:manifestacoes "ouvidoria.pdf"] [:solicitacoes-lgpd "lgpd.pdf"]]]
        (let [[a & mais] (get-in w [chave 0 :anexos])]
          (is (nil? mais) (name chave))
          (is (= {:nome nome :tipo-midia "application/pdf" :bytes (count (.getBytes ^String conteudo-pdf "UTF-8")) :origem "casa"}
                 (select-keys a [:nome :tipo-midia :bytes :origem])))
          (is (= #{:id :nome :tipo-midia :bytes :origem :enviado-em} (set (keys a))) "nem chave, nem sha256, nem quem enviou")))
      (is (not (str/includes? (:body r) (str sec))))
      (is (not (str/includes? (:body r) "atendimento/"))))
    (testing "outro cidadao nao recebe o protocolo alheio nem os seus anexos"
      (let [r (pt/response-for cid-svc :get "/portal/meus-protocolos" :headers (como ente intruso))]
        (is (= {:pedidos-esic [] :solicitacoes-lgpd [] :manifestacoes []} (ler r)))))
    (testing "item sem anexo: lista vazia (nunca ausente)"
      (let [sem (protocolo-respondido! c :esic eu sec)
            w (ler (pt/response-for cid-svc :get "/portal/meus-protocolos" :headers (como ente eu)))
            item (first (filter #(= (str sem) (:id %)) (:pedidos-esic w)))]
        (is (= [] (:anexos item)))))))

(deftest acompanhamento-publico-nao-carrega-anexo
  ;; o protocolo e' sequencial e adivinhavel: a rota publica fica no minimo (protocolo, estado, dias) — sem anexo, sem nome
  ;; de arquivo, sem rota de download
  (let [c (cenario) ente (:ente c) eu (random-uuid) sec (random-uuid) svc (servico c #{"secretario"})
        pedido (controllers/protocolar-pedido *repo* (:relogio c) (cidadao ente eu) {:assunto "Folha" :descricao "x"})
        manif (controllers/protocolar-manifestacao! *repo* (:relogio c) (cidadao ente eu)
                                                    {:tipo "reclamacao" :assunto "Fila" :descricao "Demora." :anonima false})]
    (controllers/responder-pedido! *repo* (:relogio c) (servidor ente sec) (:id pedido) {:corpo "Segue."})
    (controllers/responder-manifestacao! *repo* (:relogio c) (servidor ente sec) (:id manif) {:corpo "Segue."})
    (is (= 201 (:status (anexar svc ente sec "esic" (:id pedido) "SEGREDO-NOME-DO-ARQUIVO.pdf" "application/pdf" conteudo-pdf))))
    (is (= 201 (:status (anexar svc ente sec "ouvidoria" (:id manif) "SEGREDO-NOME-DO-ARQUIVO.pdf" "application/pdf" conteudo-pdf))))
    (testing "controle positivo: o nome do arquivo aparece para o dono (senao o teste seria cego)"
      (is (str/includes? (:body (pt/response-for (servico c #{}) :get "/portal/meus-protocolos" :headers (como ente eu)))
                         "SEGREDO-NOME-DO-ARQUIVO")))
    (doseq [caminho [(str "/portal/casa/" ente "/esic/acompanhar/" (:protocolo pedido))
                     (str "/portal/casa/" ente "/ouvidoria/acompanhar/" (:protocolo manif))]]
      (let [r (pt/response-for svc :get caminho)]
        (is (= 200 (:status r)) caminho)
        (is (= #{:protocolo :estado :dias-restantes} (set (keys (ler r)))) "a rota publica segue no minimo")
        (is (not (re-find #"(?i)anexo|SEGREDO|\.pdf|atendimento/" (:body r))) caminho)))))

;; ---------------------------------------------------------------- o banco

(deftest o-anexo-e-so-append-e-isolado-por-casa
  (let [c (cenario) ente (:ente c) sec (random-uuid) svc (servico c #{"secretario"}) ds (:ds *c*)
        id (protocolo-respondido! c :esic (random-uuid) sec)
        anexo-id (parse-uuid (get-in (anexar svc ente sec "esic" id "a.pdf" "application/pdf" conteudo-pdf) [:corpo :id]))]
    (testing "outra Casa nao ve a linha (RLS), nem pelo SQL"
      (is (= 1 (count (linhas-do-banco ente "SELECT id FROM participacao.anexo WHERE id = ?" anexo-id))))
      (is (empty? (linhas-do-banco (random-uuid) "SELECT id FROM participacao.anexo WHERE id = ?" anexo-id))))
    (testing "o role da aplicacao nao altera nem apaga (sem GRANT)"
      (doseq [sql ["UPDATE participacao.anexo SET nome = 'x' WHERE id = ?" "DELETE FROM participacao.anexo WHERE id = ?"]]
        (is (thrown? Exception (tenancy/com-tenant* ds ente #(jdbc/execute! % [sql anexo-id]))) sql)))
    (testing "nem o dono: o trigger append-only recusa UPDATE/DELETE"
      (doseq [sql ["UPDATE participacao.anexo SET nome = 'x' WHERE id = ?" "DELETE FROM participacao.anexo WHERE id = ?"]]
        (is (thrown? Exception (jdbc/execute! ds [sql anexo-id])) sql)))))

(deftest o-banco-recusa-o-que-a-aplicacao-nunca-grava
  (let [ente (random-uuid) objeto (random-uuid) ds (:ds *c*)
        base {:id (random-uuid) :objeto-tipo "pedido_esic" :origem "casa" :nome "a.pdf" :bytes 10
              :sha256 (apply str (repeat 64 "a")) :por (random-uuid)}
        inserir (fn [m]
                  (let [m (merge base m) chave (or (:chave m) (str "atendimento/" ente "/" objeto "/" (:id m)))]
                    (tenancy/com-tenant* ds ente
                      #(jdbc/execute! % ["INSERT INTO participacao.anexo (ente_id, id, objeto_tipo, objeto_id, origem, nome, tipo_midia,
                                          bytes, sha256, chave_objeto, enviado_por) VALUES (?, ?, ?, ?, ?, ?, 'application/pdf', ?, ?, ?, ?)"
                                         ente (:id m) (:objeto-tipo m) objeto (:origem m) (:nome m) (:bytes m) (:sha256 m) chave (:por m)]))))]
    (is (some? (inserir {})) "controle: a linha valida entra")
    (testing "cada CHECK reprova o seu defeito"
      (is (thrown? Exception (inserir {:id (random-uuid) :bytes 0})) "bytes > 0")
      (is (thrown? Exception (inserir {:id (random-uuid) :bytes (inc (* 10 1024 1024))})) "ate' 10 MB")
      (is (thrown? Exception (inserir {:id (random-uuid) :origem "terceiro"})) "origem casa|requerente")
      (is (thrown? Exception (inserir {:id (random-uuid) :objeto-tipo "comentario"})) "objeto_tipo dos 3 protocolos")
      (is (thrown? Exception (inserir {:id (random-uuid) :sha256 "zz"})) "sha256 hexa de 64")
      (is (thrown? Exception (inserir {:id (random-uuid) :nome "   "})) "nome nao vazio")
      (is (thrown? Exception (inserir {:id (random-uuid) :chave (str "outra-pasta/" ente "/" objeto "/x")})) "a chave da convencao"))
    (testing "a origem `requerente` e' aceita pelo banco (a fatia seguinte), mas esta fatia nunca a grava"
      (is (some? (inserir {:id (random-uuid) :origem "requerente"}))))))

(deftest a-tabela-entra-sozinha-na-exportacao-e-no-apagamento-da-casa
  ;; o inventario e' DESCOBERTO do catalogo do banco (nunca uma lista a mao): tabela com `ente_id` e RLS entra, e a
  ;; chave `atendimento/<ente>/...` e' descoberta pela convencao
  (let [inv (into {} (map (juxt inventario/nome identity)) (inventario/inventario (:ds *c*)))]
    (is (contains? inv "participacao.anexo"))
    (is (true? (:exporta? (inv "participacao.anexo"))) "isolada por RLS: vai na exportacao")
    (let [ente (random-uuid) chave (str "atendimento/" ente "/" (random-uuid) "/" (random-uuid))]
      (is (arquivos/da-convencao? ente chave))
      (is (not (arquivos/da-convencao? (random-uuid) chave)) "nunca o blob de outra Casa"))
    (testing "`atendimento/` aparece como pasta de primeiro nivel do bucket, e o blob da Casa sai na varredura por convencao"
      (let [{:keys [m store]} (store-memoria) ente (random-uuid) a (str "atendimento/" ente "/p/a") b (str "atendimento/" (random-uuid) "/p/b")]
        (swap! m assoc a (.getBytes "a") b (.getBytes "b"))
        (is (= #{a} (arquivos/por-convencao store ente #{"exportacoes/"})) "o da Casa; o de outra Casa nao")))))
