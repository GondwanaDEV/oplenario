(ns oplenario.participacao.anexos-substituir-test
  "INTEGRACAO (PG real + borda HTTP) — SUBSTITUIR um anexo da resposta (ADR-0022, \"Substituir um anexo\"). A secretaria troca
  o arquivo errado pelo certo num so' ato, a qualquer tempo (a janela de 10 minutos so' vale para ANEXAR): o antigo e'
  retirado (com o motivo, blob fora, download 404) e o novo nasce apontando para ele e ocupa a vaga. Prova: o banco
  (`substitui_anexo_id`: uma troca por anexo, mesma Casa); o efeito por especie; as recusas (do requerente, ja' trocado, ja'
  retirado, o mesmo arquivo, sem motivo, papel, outra Casa); a vaga com 5 anexos; fora da janela; a leitura no balcao e em
  /meus-protocolos; a corrida de duas secretarias; e cada ponto de falha do object storage."
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

;; ---------------------------------------------------------------- o cenario (o mesmo desenho de anexos-do-requerente-test)

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

(defn- ler [r]
  (when (seq (:body r))
    (try (json/read-value (:body r) json/keyword-keys-object-mapper) (catch Exception _ nil))))

(def ^:private fronteira "----oplenario-teste-substituir")

(defn- multipart
  "Um multipart com o arquivo e (opcional) o campo `motivo`. `motivo-depois?` poe o campo DEPOIS do arquivo."
  [motivo nome tipo conteudo & {:keys [motivo-depois?]}]
  (let [campo (when (some? motivo)
                (str "--" fronteira "\r\nContent-Disposition: form-data; name=\"motivo\"\r\n\r\n" motivo "\r\n"))
        arquivo (str "--" fronteira "\r\n"
                     "Content-Disposition: form-data; name=\"arquivo\"; filename=\"" nome "\"\r\n"
                     (when tipo (str "Content-Type: " tipo "\r\n")) "\r\n"
                     conteudo "\r\n")]
    {:ct (str "multipart/form-data; boundary=" fronteira)
     :body (str (when-not motivo-depois? campo) arquivo (when motivo-depois? campo) "--" fronteira "--\r\n")}))

(defn- substituir
  "POST /atendimento/<rota>/:id/anexos/:anexo/substituir como `quem`."
  [svc ente quem rota id anexo-id motivo nome tipo conteudo & opcoes]
  (let [{:keys [ct body]} (apply multipart motivo nome tipo conteudo opcoes)
        r (pt/response-for svc :post (str "/atendimento/" rota "/" id "/anexos/" anexo-id "/substituir")
                           :headers (assoc (como ente quem) "Content-Type" ct) :body body)]
    {:status (:status r) :corpo (ler r) :bruto (:body r)}))

(defn- anexar [svc ente quem rota id nome tipo conteudo & {:keys [portal?]}]
  (let [{:keys [ct body]} (multipart nil nome tipo conteudo)
        r (pt/response-for svc :post (if portal?
                                       (str "/portal/meus-protocolos/" rota "/" id "/anexos")
                                       (str "/atendimento/" rota "/" id "/anexos"))
                           :headers (assoc (como ente quem) "Content-Type" ct) :body body)]
    {:status (:status r) :corpo (ler r) :bruto (:body r)}))

(defn- retirar [svc ente quem rota id anexo-id motivo]
  (let [r (pt/response-for svc :post (str "/atendimento/" rota "/" id "/anexos/" anexo-id "/retirar")
                           :headers (assoc (como ente quem) "Content-Type" "application/json")
                           :body (json/write-value-as-string {:motivo motivo}))]
    {:status (:status r) :corpo (ler r)}))

(defn- linhas-do-banco [ente sql & params]
  (tenancy/com-tenant* (:ds *c*) ente #(jdbc/execute! % (into [sql] params) {:builder-fn rs/as-unqualified-maps})))

(def ^:private especies
  {:esic {:rota "esic" :chave-lista :pedidos-esic
          :protocolar (fn [c eu] (controllers/protocolar-pedido *repo* (:relogio c) (cidadao (:ente c) eu)
                                                                {:assunto "Folha" :descricao "Quero a folha."}))
          :responder! (fn [c sec id] (controllers/responder-pedido! *repo* (:relogio c) (servidor (:ente c) sec) id {:corpo "Segue."}))}
   :ouvidoria {:rota "ouvidoria" :chave-lista :manifestacoes
               :protocolar (fn [c eu] (controllers/protocolar-manifestacao! *repo* (:relogio c) (cidadao (:ente c) eu)
                                                                            {:tipo "reclamacao" :assunto "Fila" :descricao "Demora." :anonima false}))
               :responder! (fn [c sec id] (controllers/responder-manifestacao! *repo* (:relogio c) (servidor (:ente c) sec) id {:corpo "Segue."}))}
   :lgpd {:rota "lgpd" :chave-lista :solicitacoes-lgpd
          :protocolar (fn [c eu] (controllers/solicitar-titular! *repo* (:relogio c) (cidadao (:ente c) eu)
                                                                 {:tipo "acessar" :detalhe "Meus dados."}))
          :responder! (fn [c sec id] (controllers/responder-solicitacao! *repo* (:relogio c) (servidor (:ente c) sec) id {:corpo "Segue."}))}})

(def ^:private pdf-errado "%PDF-1.4 planilha ERRADA da folha de 2025")
(def ^:private pdf-certo "%PDF-1.4 planilha CERTA da folha de 2025")

(defn- chave [ente id anexo-id] (str "atendimento/" ente "/" id "/" anexo-id))

(defn- protocolo!
  "Protocola (como `eu`), responde (como `sec`) e a secretaria anexa `pdf-errado`. Devolve o cenario do protocolo:
  {:c :ente :eu :sec :id :rota :aid :svc (secretaria) :cid (cidadao)}."
  [especie]
  (let [c (cenario) ente (:ente c) eu (random-uuid) sec (random-uuid)
        {:keys [rota protocolar responder!]} (especies especie)
        id (:id (protocolar c eu))
        _ (responder! c sec id)
        svc (servico c #{"secretario"})
        aid (get-in (anexar svc ente sec rota id "folha.pdf" "application/pdf" pdf-errado) [:corpo :id])]
    {:c c :ente ente :eu eu :sec sec :id id :rota rota :aid aid :svc svc :cid (servico c #{}) :especie especie}))

(defn- detalhe [{:keys [svc ente sec rota id]}]
  (ler (pt/response-for svc :get (str "/atendimento/" rota "/" id) :headers (como ente sec))))

(defn- baixar [svc ente quem rota id anexo-id & {:keys [portal?]}]
  (pt/response-for svc :get (if portal?
                              (str "/portal/meus-protocolos/" rota "/" id "/anexos/" anexo-id)
                              (str "/atendimento/" rota "/" id "/anexos/" anexo-id))
                   :headers (como ente quem)))

(defn- estado-do-storage [{:keys [c]}] @(:m c))

;; ---------------------------------------------------------------- o banco: uma troca por anexo, na mesma Casa

(defn- inserir-cru!
  "Insere direto uma linha de anexo (da Casa) apontando para `substitui`, pela mesma tx do tenant: o banco e' quem recusa."
  [ente objeto-id substitui]
  (let [id (random-uuid)]
    (linhas-do-banco ente
      (str "INSERT INTO participacao.anexo (ente_id, id, objeto_tipo, objeto_id, origem, nome, tipo_midia, bytes, sha256,"
           " chave_objeto, enviado_por, substitui_anexo_id)"
           " VALUES (?::uuid, ?::uuid, 'pedido_esic', ?::uuid, 'casa', 'x.pdf', 'application/pdf', 5, ?, ?, ?::uuid, ?::uuid)"
           " RETURNING id")
      (str ente) (str id) (str objeto-id) (apply str (repeat 64 "a")) (chave ente objeto-id id) (str (random-uuid))
      (some-> substitui str))))

(defn- sqlstate
  "nil se `f` passou; o SQLState da excecao (olhando as causas) se o banco recusou; `:outra` se foi outro erro."
  [f]
  (try (f) nil
       (catch Exception e
         (or (some #(when (instance? java.sql.SQLException %) (.getSQLState ^java.sql.SQLException %))
                   (take-while some? (iterate ex-cause e)))
             :outra))))

(deftest o-banco-amarra-a-substituicao-uma-vez-por-anexo-e-na-mesma-casa
  (let [{:keys [ente id aid]} (protocolo! :esic)]
    (testing "a coluna existe e e' nula num anexo comum"
      (is (nil? (:substitui_anexo_id (first (linhas-do-banco ente "SELECT substitui_anexo_id FROM participacao.anexo WHERE id = ?::uuid" (str aid)))))))
    (testing "uma linha nova pode apontar para o anexo antigo; uma SEGUNDA para o mesmo antigo e' recusada (indice unico)"
      (is (some? (inserir-cru! ente id aid)))
      (is (= "23505" (sqlstate #(inserir-cru! ente id aid)))))
    (testing "apontar para um anexo que nao existe e' recusado (FK)"
      (is (= "23503" (sqlstate #(inserir-cru! ente id (random-uuid))))))
    (testing "apontar para o anexo de OUTRA Casa e' recusado (a FK e' composta com o ente)"
      (let [outro (protocolo! :esic)]
        (is (= "23503" (sqlstate #(inserir-cru! ente id (:aid outro)))))))
    (testing "a tabela segue so' SELECT/INSERT: UPDATE do anexo e' recusado"
      (is (some? (sqlstate #(linhas-do-banco ente "UPDATE participacao.anexo SET substitui_anexo_id = NULL WHERE id = ?::uuid" (str aid))))))))

;; ---------------------------------------------------------------- o efeito, por especie

(deftest substituir-troca-o-arquivo-nas-tres-especies
  (doseq [especie [:esic :ouvidoria :lgpd]]
    (testing (name especie)
      (let [{:keys [c ente eu sec id rota aid svc cid] :as p} (protocolo! especie)
            r (substituir svc ente sec rota id aid "arquivo errado: era a folha de 2024" "folha-certa.pdf" "application/pdf" pdf-certo)
            novo-id (get-in r [:corpo :id])]
        (is (= 201 (:status r)) (str (:bruto r)))
        (testing "o 201 e' o anexo NOVO, no contrato de sempre: sem chave, sem sha256, sem quem enviou"
          (is (= #{:id :nome :tipo-midia :bytes :origem :enviado-em} (set (keys (:corpo r)))))
          (is (= {:nome "folha-certa.pdf" :origem "casa"} (select-keys (:corpo r) [:nome :origem])))
          (is (not= (str aid) novo-id))
          (is (not (str/includes? (:bruto r) (str sec))))
          (is (not (str/includes? (:bruto r) "atendimento/"))))
        (testing "o blob antigo saiu do storage; o novo esta na chave da convencao"
          (is (not (contains? (estado-do-storage p) (chave ente id aid))))
          (is (= pdf-certo (String. ^bytes (get (estado-do-storage p) (chave ente id novo-id)) "UTF-8"))))
        (testing "o antigo: download 404 para a secretaria e para o dono; o novo: baixavel pelos dois"
          (is (= 404 (:status (baixar svc ente sec rota id aid))))
          (is (= 404 (:status (baixar cid ente eu rota id aid :portal? true))))
          (let [r1 (baixar svc ente sec rota id novo-id) r2 (baixar cid ente eu rota id novo-id :portal? true)]
            (is (= 200 (:status r1)))
            (is (= 200 (:status r2)))
            (is (= pdf-certo (:body r2)))))
        (testing "o banco: a retirada do antigo com o motivo e quem retirou; o novo aponta para o antigo; so' INSERTs"
          (let [[ret] (linhas-do-banco ente "SELECT motivo, retirado_por FROM participacao.anexo_retirada WHERE anexo_id = ?::uuid" (str aid))
                [novo] (linhas-do-banco ente "SELECT origem, substitui_anexo_id, enviado_por FROM participacao.anexo WHERE id = ?::uuid" novo-id)]
            (is (= {:motivo "arquivo errado: era a folha de 2024" :retirado_por sec} ret))
            (is (= {:origem "casa" :substitui_anexo_id aid :enviado_por sec}
                   (update novo :substitui_anexo_id str)) "o novo aponta para o antigo")
            (is (= 2 (count (linhas-do-banco ente "SELECT 1 FROM participacao.anexo WHERE objeto_id = ?::uuid" (str id)))))))
        (testing "o instante da retirada e o do novo sao o mesmo (um ato so')"
          (let [[l] (linhas-do-banco ente (str "SELECT a.enviado_em = r.retirado_em AS igual FROM participacao.anexo a, participacao.anexo_retirada r"
                                               " WHERE a.id = ?::uuid AND r.anexo_id = ?::uuid") novo-id (str aid))]
            (is (true? (:igual l)))))))))

(deftest o-motivo-pode-vir-depois-do-arquivo-e-e-aparado
  (let [{:keys [ente sec id rota aid svc]} (protocolo! :esic)
        r (substituir svc ente sec rota id aid "  planilha de outro mes  " "certa.pdf" "application/pdf" pdf-certo :motivo-depois? true)]
    (is (= 201 (:status r)) (str (:bruto r)))
    (is (= "planilha de outro mes"
           (:motivo (first (linhas-do-banco ente "SELECT motivo FROM participacao.anexo_retirada WHERE anexo_id = ?::uuid" (str aid))))))))

(deftest o-motivo-aceita-acento
  (let [{:keys [ente sec id rota aid svc]} (protocolo! :esic)
        r (substituir svc ente sec rota id aid "Versão errada: não é a folha de março" "certa.pdf" "application/pdf" pdf-certo)]
    (is (= 201 (:status r)))
    (is (= "Versão errada: não é a folha de março"
           (:motivo (first (linhas-do-banco ente "SELECT motivo FROM participacao.anexo_retirada WHERE anexo_id = ?::uuid" (str aid))))))))

;; ---------------------------------------------------------------- a qualquer tempo, e a vaga

(deftest vale-fora-da-janela-de-dez-minutos
  (let [{:keys [c ente sec id rota aid svc]} (protocolo! :esic)]
    (avancar! c (* 3 3600))
    (testing "anexar um arquivo NOVO ja' nao cabe (a janela fechou)..."
      (is (= 409 (:status (anexar svc ente sec rota id "outro.pdf" "application/pdf" "%PDF-1.4 outro")))))
    (testing "...mas substituir o errado pelo certo vale a qualquer hora"
      (is (= 201 (:status (substituir svc ente sec rota id aid "errei" "certa.pdf" "application/pdf" pdf-certo)))))))

(deftest o-novo-ocupa-a-vaga-do-antigo-mesmo-com-cinco-anexos
  (let [{:keys [ente sec id rota aid svc] :as p} (protocolo! :esic)]
    (dotimes [i 4] (is (= 201 (:status (anexar svc ente sec rota id (str "a" i ".txt") "text/plain" (str "conteudo " i))))))
    (is (= 5 (count (filter #(nil? (:retirado-em %)) (:anexos (detalhe p))))))
    (is (= 409 (:status (anexar svc ente sec rota id "sexto.txt" "text/plain" "sexto"))) "cheio: nenhum anexo novo cabe")
    (let [r (substituir svc ente sec rota id aid "errado" "certa.pdf" "application/pdf" pdf-certo)]
      (is (= 201 (:status r)) (str (:bruto r)))
      (let [d (detalhe p)]
        (is (= 6 (count (:anexos d))) "o antigo fica na lista como registro")
        (is (= 5 (count (filter #(nil? (:retirado-em %)) (:anexos d)))) "continuam 5 vigentes")
        (is (false? (get-in d [:acoes :pode-anexar])) "e o limite de 5 segue valendo")))))

;; ---------------------------------------------------------------- as recusas

(deftest o-anexo-do-requerente-nao-se-substitui
  (let [{:keys [c ente eu sec id rota svc cid] :as p} (protocolo! :esic)
        ;; o requerente anexa ao proprio pedido (dentro dos 10 minutos do protocolo)
        meu (get-in (anexar cid ente eu rota id "meu.pdf" "application/pdf" "%PDF-1.4 meu" :portal? true) [:corpo :id])
        antes (estado-do-storage p)
        r (substituir svc ente sec rota id meu "quero trocar" "x.pdf" "application/pdf" pdf-certo)]
    (is (some? meu))
    (is (= 409 (:status r)))
    (is (str/includes? (get-in r [:corpo :erro]) "Só se substitui o arquivo da Casa"))
    (is (= antes (estado-do-storage p)) "nada mudou no storage: nem blob novo, nem o do requerente saiu")
    (is (zero? (count (linhas-do-banco ente "SELECT 1 FROM participacao.anexo_retirada WHERE anexo_id = ?::uuid" (str meu)))))
    (testing "o que o requerente anexou continua podendo ser retirado"
      (is (= 200 (:status (retirar svc ente sec rota id meu "documento de terceiro")))))))

(deftest anexo-ja-substituido-ou-ja-retirado-409
  (let [{:keys [ente sec id rota aid svc] :as p} (protocolo! :esic)
        r1 (substituir svc ente sec rota id aid "errado" "certa.pdf" "application/pdf" pdf-certo)
        novo (get-in r1 [:corpo :id])]
    (is (= 201 (:status r1)))
    (testing "o ANTIGO ja' foi substituido: 409 em palavras, e nada novo no storage"
      (let [antes (estado-do-storage p)
            r (substituir svc ente sec rota id aid "de novo" "outra.pdf" "application/pdf" "%PDF-1.4 outra")]
        (is (= 409 (:status r)))
        (is (= "Este arquivo já foi substituído por outro." (get-in r [:corpo :erro])))
        (is (= antes (estado-do-storage p)))))
    (testing "o NOVO e' um anexo da Casa como outro qualquer: pode ser substituido (a cadeia segue)"
      (is (= 201 (:status (substituir svc ente sec rota id novo "ainda errado" "terceira.pdf" "application/pdf" "%PDF-1.4 terceira")))))
    (testing "um anexo ja' RETIRADO (sem troca) nao se substitui"
      (let [outro (get-in (anexar svc ente sec rota id "avulso.txt" "text/plain" "avulso") [:corpo :id])
            _ (is (= 200 (:status (retirar svc ente sec rota id outro "enviado por engano"))))
            r (substituir svc ente sec rota id outro "troca" "x.pdf" "application/pdf" "%PDF-1.4 x")]
        (is (= 409 (:status r)))
        (is (= "Este arquivo já foi retirado e não pode ser substituído." (get-in r [:corpo :erro])))))))

(deftest o-mesmo-arquivo-ja-anexado-409-sem-blob-orfao
  (let [{:keys [ente sec id rota aid svc] :as p} (protocolo! :esic)
        antes (estado-do-storage p)]
    (testing "trocar o anexo por ele mesmo (mesmo conteudo) nao troca nada"
      (let [r (substituir svc ente sec rota id aid "igual" "folha.pdf" "application/pdf" pdf-errado)]
        (is (= 409 (:status r)))
        (is (str/includes? (get-in r [:corpo :erro]) "Este mesmo arquivo já está anexado"))))
    (testing "nem pelo conteudo de OUTRO anexo vigente da Casa"
      (anexar svc ente sec rota id "b.pdf" "application/pdf" pdf-certo)
      (let [r (substituir svc ente sec rota id aid "igual ao outro" "c.pdf" "application/pdf" pdf-certo)]
        (is (= 409 (:status r)))))
    (testing "recusa comprovada: o blob que subiu foi tirado, o antigo segue la', sem retirada gravada"
      (is (= (count antes) (dec (count (estado-do-storage p)))) "so' o b.pdf entrou a mais")
      (is (contains? (estado-do-storage p) (chave ente id aid)))
      (is (zero? (count (linhas-do-banco ente "SELECT 1 FROM participacao.anexo_retirada")))))))

(deftest motivo-obrigatorio-conferido-antes-de-gravar-o-blob
  (let [{:keys [ente sec id rota aid svc] :as p} (protocolo! :esic)
        antes (estado-do-storage p)]
    (doseq [motivo [nil "" "   " (apply str (repeat 1001 "m"))]]
      (let [r (substituir svc ente sec rota id aid motivo "certa.pdf" "application/pdf" pdf-certo)]
        (is (= 400 (:status r)) (str "motivo: " (pr-str (some-> motivo (subs 0 (min 5 (count motivo)))))))))
    (is (= antes (estado-do-storage p)) "400 antes de gravar: nenhum blob novo")
    (is (zero? (count (linhas-do-banco ente "SELECT 1 FROM participacao.anexo_retirada"))))
    (testing "um campo `motivo` enorme (acima de 4 KB) e' recusado ao ler, sem estourar nada"
      (is (= 400 (:status (substituir svc ente sec rota id aid (apply str (repeat 5000 "m")) "certa.pdf" "application/pdf" pdf-certo)))))))

(deftest tipo-e-conteudo-seguem-as-regras-do-envio
  (let [{:keys [ente sec id rota aid svc] :as p} (protocolo! :esic)
        antes (estado-do-storage p)]
    (testing "tipo fora da lista: 415"
      (is (= 415 (:status (substituir svc ente sec rota id aid "x" "malware.exe" "application/octet-stream" "MZ...")))))
    (testing "extensao .pdf com conteudo que nao e' PDF: 415"
      (is (= 415 (:status (substituir svc ente sec rota id aid "x" "falso.pdf" "application/pdf" "isto nao e' um pdf")))))
    (testing "sem arquivo (so' o campo): 400; arquivo vazio: 400"
      (let [r (pt/response-for svc :post (str "/atendimento/" rota "/" id "/anexos/" aid "/substituir")
                               :headers (assoc (como ente sec) "Content-Type" (str "multipart/form-data; boundary=" fronteira))
                               :body (str "--" fronteira "\r\nContent-Disposition: form-data; name=\"motivo\"\r\n\r\nx\r\n--" fronteira "--\r\n"))]
        (is (= 400 (:status r))))
      (is (= 400 (:status (substituir svc ente sec rota id aid "x" "vazio.pdf" "application/pdf" "")))))
    (is (= antes (estado-do-storage p)) "nada gravado, nada retirado")
    (is (contains? (estado-do-storage p) (chave ente id aid)))))

(deftest papel-alvo-e-casa-certos
  (let [{:keys [c ente eu sec id rota aid svc cid] :as p} (protocolo! :esic)
        outro (protocolo! :esic)
        antes (estado-do-storage p)
        tenta (fn [s e q rt i a] (substituir s e q rt i a "x" "certa.pdf" "application/pdf" pdf-certo))]
    (testing "quem nao e' secretario nao troca: 403 (o proprio dono, a vereadora)"
      (is (= 403 (:status (tenta cid ente eu rota id aid))))
      (is (= 403 (:status (tenta (servico c #{"vereador"}) ente sec rota id aid))))
      (is (= 403 (:status (tenta (servico c #{"juridico"}) ente sec rota id aid)))))
    (testing "sem token: 401"
      (let [{:keys [ct body]} (multipart "x" "certa.pdf" "application/pdf" pdf-certo)]
        (is (= 401 (:status (pt/response-for svc :post (str "/atendimento/" rota "/" id "/anexos/" aid "/substituir")
                                             :headers {"Content-Type" ct} :body body))))))
    (testing "outra Casa, protocolo errado, anexo inexistente, especie errada: 404, nada muda"
      (is (= 404 (:status (tenta (servico c #{"secretario"}) (random-uuid) sec rota id aid))))
      (is (= 404 (:status (tenta svc ente sec rota (:id outro) aid))) "o anexo e' de outro protocolo (e de outra Casa)")
      (is (= 404 (:status (tenta svc ente sec rota id (random-uuid)))))
      (is (= 404 (:status (tenta svc ente sec "ouvidoria" id aid)))))
    (is (= antes (estado-do-storage p)))
    (is (zero? (count (linhas-do-banco ente "SELECT 1 FROM participacao.anexo_retirada"))))))

(deftest o-corpo-nao-e-lido-quando-o-alvo-nao-cabe
  ;; a conferencia roda ANTES do multipart: um 409/404 nao gasta uma vaga de envio nem le o corpo
  (let [{:keys [c ente eu sec id rota svc cid]} (protocolo! :esic)
        meu (get-in (anexar cid ente eu rota id "meu.pdf" "application/pdf" "%PDF-1.4 meu" :portal? true) [:corpo :id])
        lido (atom 0)
        bruto (ByteArrayInputStream. (.getBytes ^String (:body (multipart "x" "c.pdf" "application/pdf" pdf-certo)) "UTF-8"))
        corpo (proxy [java.io.FilterInputStream] [bruto]
                (read
                  ([] (swap! lido inc) (.read bruto))
                  ([^bytes b] (let [n (.read bruto b)] (when (pos? n) (swap! lido + n)) n))
                  ([^bytes b off len] (let [n (.read bruto b (int off) (int len))] (when (pos? n) (swap! lido + n)) n))))
        r (pt/response-for svc :post (str "/atendimento/" rota "/" id "/anexos/" meu "/substituir")
                           :headers (assoc (como ente sec) "Content-Type" (str "multipart/form-data; boundary=" fronteira))
                           :body corpo)]
    (is (some? c))
    (is (= 409 (:status r)))
    (is (zero? @lido) "nenhum byte do corpo foi lido")))

;; ---------------------------------------------------------------- quem ve o que

(deftest o-balcao-ve-o-antigo-substituido-com-o-motivo-e-o-novo
  (let [{:keys [ente sec id rota aid svc] :as p} (protocolo! :esic)
        novo (get-in (substituir svc ente sec rota id aid "arquivo errado" "certa.pdf" "application/pdf" pdf-certo) [:corpo :id])
        d (detalhe p)
        por-id (into {} (map (juxt :id identity) (:anexos d)))]
    (is (= 2 (count (:anexos d))))
    (testing "o antigo: retirado em <data>, com o motivo (so' o balcao le), substituido pelo novo"
      (let [a (por-id (str aid))]
        (is (some? (:retirado-em a)))
        (is (= "arquivo errado" (:motivo-da-retirada a)))
        (is (= novo (:substituido-por a)))))
    (testing "o novo: vigente, sem retirada, sem marca de substituido"
      (let [a (por-id novo)]
        (is (nil? (:retirado-em a)))
        (is (nil? (:substituido-por a)))
        (is (= "certa.pdf" (:nome a)))))
    (testing "o contrato aceita os campos novos sem vazar nada (a projecao e' validada)"
      (is (not (str/includes? (json/write-value-as-string d) (str sec)))))))

(deftest o-dono-ve-substituido-sem-motivo-e-baixa-o-novo
  (doseq [especie [:esic :ouvidoria :lgpd]]
    (testing (name especie)
      (let [{:keys [ente eu sec id rota aid svc cid]} (protocolo! especie)
            novo (get-in (substituir svc ente sec rota id aid "motivo so' do balcao" "certa.pdf" "application/pdf" pdf-certo) [:corpo :id])
            r (pt/response-for cid :get "/portal/meus-protocolos" :headers (como ente eu))
            item (first (filter #(= (str id) (:id %)) (get (ler r) (get-in especies [especie :chave-lista]))))
            por-id (into {} (map (juxt :id identity) (:anexos item)))]
        (is (= 200 (:status r)))
        (is (= 2 (count (:anexos item))))
        (is (some? (:retirado-em (por-id (str aid)))))
        (is (= novo (:substituido-por (por-id (str aid)))))
        (is (nil? (:substituido-por (por-id novo))))
        (is (not (str/includes? (:body r) "motivo so' do balcao")) "o motivo nunca chega ao dono")
        (is (not (contains? (por-id (str aid)) :motivo-da-retirada)))
        (is (= 200 (:status (baixar cid ente eu rota id novo :portal? true))))
        (is (= 404 (:status (baixar cid ente eu rota id aid :portal? true))))))))

(deftest o-acompanhamento-publico-segue-sem-anexo
  (let [{:keys [ente sec id rota aid svc cid] :as p} (protocolo! :esic)]
    (substituir svc ente sec rota id aid "x" "certa.pdf" "application/pdf" pdf-certo)
    (let [recibo (first (linhas-do-banco ente "SELECT protocolo FROM participacao.pedido_esic WHERE id = ?::uuid" (str id)))
          r (pt/response-for cid :get (str "/portal/casa/" ente "/esic/acompanhar/" (:protocolo recibo)))]
      (is (some? p))
      (is (not (str/includes? (str (:body r)) "anexo")) "a rota publica por numero de protocolo nao muda: nao carrega anexo"))))

;; ---------------------------------------------------------------- a corrida de duas secretarias

(deftest duas-secretarias-substituindo-o-mesmo-anexo-uma-vence
  (let [{:keys [c ente sec id rota aid] :as p} (protocolo! :esic)
        outra (random-uuid)
        svc (servico c #{"secretario"})
        corrida (fn [quem conteudo] (future (substituir svc ente quem rota id aid "corrida" "certa.pdf" "application/pdf" conteudo)))
        rs (mapv deref [(corrida sec "%PDF-1.4 versao A") (corrida outra "%PDF-1.4 versao B")])]
    (is (= [201 409] (sort (map :status rs))))
    (is (= 1 (count (linhas-do-banco ente "SELECT 1 FROM participacao.anexo WHERE substitui_anexo_id = ?::uuid" (str aid)))))
    (is (= 1 (count (linhas-do-banco ente "SELECT 1 FROM participacao.anexo_retirada WHERE anexo_id = ?::uuid" (str aid)))))
    (testing "no storage so' o blob do vencedor: o do antigo saiu e o do perdedor foi tirado"
      (is (= 1 (count (estado-do-storage p))))
      (is (not (contains? (estado-do-storage p) (chave ente id aid)))))))

;; ---------------------------------------------------------------- cada ponto de falha do object storage

(defn- com-store
  "O cenario `c` com o object storage embrulhado: `guardar!`/`remover!` consultam `falha?` (fn [operacao chave] -> bool) e, se
  der true, lancam. O resto vai ao store em memoria do cenario."
  [c falha?]
  (let [base (:store c)]
    (assoc c :store #_{:clj-kondo/ignore [:missing-protocol-method]}
           (reify store/ObjetoStore
             (guardar! [_ k b t] (when (falha? :guardar k) (throw (ex-info "storage fora do ar" {}))) (store/guardar! base k b t))
             (obter [_ k] (store/obter base k))
             (abrir [_ k] (store/abrir base k))
             (remover! [_ k] (when (falha? :remover k) (throw (ex-info "storage fora do ar" {}))) (store/remover! base k))
             (listar [_ p r] (store/listar base p r))))))

(deftest falha-ao-subir-o-novo-nada-muda-e-o-antigo-segue-la
  (let [{:keys [c ente sec id rota aid] :as p} (protocolo! :esic)
        antes (estado-do-storage p)
        svc (servico (com-store c (fn [op _] (= op :guardar))) #{"secretario"})
        r (substituir svc ente sec rota id aid "errado" "certa.pdf" "application/pdf" pdf-certo)]
    (is (= 500 (:status r)))
    (is (= antes (estado-do-storage p)) "o antigo segue no storage e nao ha blob novo")
    (is (zero? (count (linhas-do-banco ente "SELECT 1 FROM participacao.anexo_retirada"))) "nada retirado")
    (is (= 1 (count (linhas-do-banco ente "SELECT 1 FROM participacao.anexo WHERE objeto_id = ?::uuid" (str id)))) "nenhuma linha nova")
    (is (= 200 (:status (baixar (:svc p) ente sec rota id aid))) "o antigo ainda baixa")))

(deftest falha-ao-tirar-o-blob-antigo-a-troca-esta-feita-e-retirar-conclui
  (let [{:keys [c ente sec id rota aid] :as p} (protocolo! :esic)
        chave-antiga (chave ente id aid)
        svc (servico (com-store c (fn [op k] (and (= op :remover) (= k chave-antiga)))) #{"secretario"})
        r (substituir svc ente sec rota id aid "errado" "certa.pdf" "application/pdf" pdf-certo)
        novo (get-in r [:corpo :id])]
    (testing "a troca foi gravada (201): o novo existe e baixa; o antigo esta retirado e o download dele e' 404"
      (is (= 201 (:status r)))
      (is (= 200 (:status (baixar svc ente sec rota id novo))))
      (is (= 404 (:status (baixar svc ente sec rota id aid)))))
    (testing "o blob antigo ficou orfao (nao saiu), mas nunca fica o protocolo sem arquivo: o novo esta la'"
      (is (contains? (estado-do-storage p) chave-antiga))
      (is (contains? (estado-do-storage p) (chave ente id novo))))
    (testing "`retirar` sobre o antigo (storage de volta) conclui a remocao: idempotente"
      (is (= 200 (:status (retirar (:svc p) ente sec rota id aid "concluindo"))))
      (is (not (contains? (estado-do-storage p) chave-antiga))))))

;; ---------------------------------------------------------------- a tabela de rotas

(deftest as-tres-rotas-de-substituir-sao-do-balcao
  (let [c (cenario) d (deps c #{}) auth (:auth d)
        rotas (filter (fn [[caminho verbo]] (and (= :post verbo) (str/ends-with? caminho "/substituir")))
                      (participacao-http/rotas d))]
    (is (= #{"/atendimento/esic/:id/anexos/:anexo/substituir" "/atendimento/ouvidoria/:id/anexos/:anexo/substituir"
             "/atendimento/lgpd/:id/anexos/:anexo/substituir"}
           (set (map first rotas)))
        "so' do balcao: nenhuma sob /portal (cidadao) nem publica")
    (is (every? (fn [[_ _ cadeia]] (some #(identical? auth %) cadeia)) rotas) "todas exigem a autenticacao")
    (is (every? (fn [[_ _ cadeia]] (some #(= (:name (it/exige-papel "secretario")) (:name %)) (filter map? cadeia))) rotas)
        "e o papel secretario")))
