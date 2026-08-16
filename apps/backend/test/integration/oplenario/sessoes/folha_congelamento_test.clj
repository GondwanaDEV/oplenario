(ns oplenario.sessoes.folha-congelamento-test
  "INTEGRACAO (PG + MinIO real) — `sessoes.controllers/gerar-folha!` (Etapa 5 fatia 4, D2/D3/D4/D6/D7/D8/D9):
  O CONGELAMENTO. Renderiza HTML+PDF, hasheia os dois, insere a linha versionada em `sessoes.folha_sessao`
  (mig 0073) e guarda os DOIS binarios no objeto_store, DEPOIS do INSERT (ancora-primeiro). Cobre: as duas
  representacoes recuperaveis com hash conferindo; D7 (a versao IMPRESSA no papel — versao 2 tem HTML
  diferente da versao 1 do MESMO dado); a CORRIDA (duas geracoes concorrentes nao duplicam versao, a
  perdedora re-renderiza); D6 (sessao aberta lanca); D8 (documento invalido lanca ANTES de renderizar); D9
  (dedup de 30s); multi-tenant (RLS); e a imutabilidade da tabela (trigger barra UPDATE/DELETE)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.components.repositorio :as repo-cadastros]
            [oplenario.config :as config]
            [oplenario.kernel.components.objeto-store :as os]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.components.renderizador-pdf :as renderizador-pdf]
            [oplenario.sessoes.components.repositorio :as repo-sessoes]
            [oplenario.sessoes.components.serializador-folha :as ser-folha]
            [oplenario.sessoes.controllers :as controllers]
            [oplenario.sessoes.db.sessao :as sessao]
            [oplenario.sistema :as sistema])
  (:import (java.security MessageDigest)
           (java.time Instant LocalDate)))

(def ^:dynamic *sys* nil)
(def ^:dynamic *repo-s* nil)
(def ^:dynamic *repo-c* nil)

(use-fixtures :once
  (fn [t]
    (let [s (component/start (sistema/novo-sistema (config/carregar)))]
      (migracao/migrar! (:ds (:datasource s)))
      (binding [*sys* s
                *repo-s* (:repo-sessoes s)
                *repo-c* (:repo-cadastros s)]
        (try (t) (finally (component/stop s)))))))

;; ---------- seeds (produtores REAIS dos dois modulos, mesma forma de folha-controller-test) ----------

(defn- casa! [ente]
  (repo-cadastros/criar-ente! *repo-c* ente
    {:ente-id ente :municipio-ibge "2304400" :nome-oficial "Camara Municipal de Fortaleza" :nome-curto "CMF"})
  (let [leg (random-uuid)]
    (repo-cadastros/criar-legislatura! *repo-c* ente
      {:id leg :ente-id ente :numero 7 :ano-inicio 2025 :ano-fim 2028 :vigente true})
    leg))

(defn- vereador-com-mandato! [ente leg nome]
  (let [id (random-uuid)]
    (repo-cadastros/criar-vereador! *repo-c* ente {:id id :ente-id ente :nome nome
                                                   :nome-parlamentar nil :identidade-id nil})
    (repo-cadastros/criar-mandato! *repo-c* ente
      {:id (random-uuid) :ente-id ente :vereador-id id :legislatura-id leg :estado "vigente"
       :partido "PX" :vigencia-inicio (LocalDate/of 2025 1 1) :vigencia-fim nil})
    id))

(defn- roster-seam [] (fn [ente-id data] (repo-cadastros/roster-da-casa *repo-c* ente-id data)))
(defn- dados-da-casa-seam []
  (fn [ente-id _data]
    (let [ente (repo-cadastros/buscar-ente *repo-c* ente-id)
          leg (repo-cadastros/legislatura-vigente *repo-c* ente-id)]
      {:nome-oficial (:nome-oficial ente) :nome-curto (:nome-curto ente)
       :legislatura-numero (:numero leg) :legislatura-ano-inicio (:ano-inicio leg)
       :legislatura-ano-fim (:ano-fim leg)})))

(defn- ator [ente] {:ente-id ente :identidade-id (random-uuid) :papeis #{"secretario"}})

(defn- abrir-e-encerrar!
  "Agenda -> abre -> encerra, sem eventos de presenca (a folha nao precisa de nenhum p/ o congelamento em si
  — os testes de agregacao ja' vivem em folha-controller-test/folha-db-test, Fatia 1)."
  [ente]
  (let [sid (repo-sessoes/transacao *repo-s* ente
              (fn [tx]
                (let [{sid :id} (sessao/agendar! tx {:id (random-uuid) :ente-id ente
                                                     :sessao-legislativa-id (random-uuid)
                                                     :tipo-sessao "ordinaria" :agendada-para (Instant/now)})]
                  (sessao/transicionar! tx {:id sid :ente-id ente :para "aberta" :lock-version 0})
                  sid)))]
    (repo-sessoes/transacao *repo-s* ente
      (fn [tx] (sessao/transicionar! tx {:id sid :ente-id ente :para "encerrada" :lock-version 1})))
    sid))

(defn- m-ports []
  {:serializador (ser-folha/serializador-folha-html)
   :renderizador-pdf (renderizador-pdf/renderizador-pdf)
   :objeto-store (:objeto-store *sys*)})

(defn- gerar! [sid a]
  (controllers/gerar-folha! *repo-s* (roster-seam) (dados-da-casa-seam) a sid
                            (tempo/relogio-fixo (Instant/now)) (m-ports)))

(defn- sha256-hex [^bytes b]
  (let [h (.digest (MessageDigest/getInstance "SHA-256") b)]
    (str "sha256:" (apply str (map #(format "%02x" (bit-and (int %) 0xff)) h)))))

;; ---------- congela: as DUAS representacoes ficam recuperaveis, hashes conferindo ----------

(deftest congela-e-as-duas-representacoes-ficam-recuperaveis-com-hash-conferindo
  (let [ente (random-uuid) leg (casa! ente)
        _v1 (vereador-com-mandato! ente leg "Ana")
        sid (abrir-e-encerrar! ente)
        a (ator ente)
        row (gerar! sid a)]
    (is (= 1 (:versao row)) "primeiro congelamento desta sessao -> versao 1")
    (is (str/starts-with? (:html-hash row) "sha256:"))
    (is (str/starts-with? (:pdf-hash row) "sha256:"))
    (let [html (os/obter (:objeto-store *sys*) (:html-objeto-store-ref row))
          pdf (os/obter (:objeto-store *sys*) (:pdf-objeto-store-ref row))]
      (is (some? html) "o HTML esta' no objeto_store sob o ref carimbado")
      (is (some? pdf) "o PDF esta' no objeto_store sob o ref carimbado")
      (is (= (:html-hash row) (sha256-hex html)) "o hash gravado bate com os bytes do HTML recuperado")
      (is (= (:pdf-hash row) (sha256-hex pdf)) "o hash gravado bate com os bytes do PDF recuperado")
      (is (str/starts-with? (String. ^bytes html "UTF-8") "<!DOCTYPE html>"))
      (is (= "application/pdf" (:pdf-content-type row))))))

;; ---------- D7: a versao IMPRESSA no papel — versao 2 tem HTML DIFERENTE da versao 1 do MESMO dado ----------

(deftest versao-2-do-mesmo-dado-tem-html-diferente-da-versao-1
  (let [ente (random-uuid) leg (casa! ente)
        _v1 (vereador-com-mandato! ente leg "Ana")
        sid (abrir-e-encerrar! ente)
        ;; DOIS atores distintos: evita D9 (dedup de 30s e' por ator) sem precisar mexer no relogio.
        row1 (gerar! sid (ator ente))
        row2 (gerar! sid (ator ente))
        html1 (String. ^bytes (os/obter (:objeto-store *sys*) (:html-objeto-store-ref row1)) "UTF-8")
        html2 (String. ^bytes (os/obter (:objeto-store *sys*) (:html-objeto-store-ref row2)) "UTF-8")]
    (is (= 1 (:versao row1)))
    (is (= 2 (:versao row2)) "MAX+1 — a segunda geracao NAO reusa a versao 1 (atores diferentes -> sem dedup)")
    (is (not= html1 html2) "MESMO dado de sessao, versoes diferentes -> bytes DIFERENTES (a versao esta' no papel)")
    (is (not= (:html-hash row1) (:html-hash row2)))
    (is (str/includes? html1 "versão 1 deste congelamento"))
    (is (str/includes? html2 "versão 2 deste congelamento"))))

;; ---------- CORRIDA: duas geracoes concorrentes nao duplicam versao; a perdedora re-renderiza ----------

(deftest corrida-duas-geracoes-concorrentes-nao-produzem-versoes-duplicadas
  ;; Interleaving DETERMINISTICO (sem thread, sem sleep) — mesmo seam de `folha-controller-test/
  ;; documento-e-um-so-snapshot...`: um ESPIAO envolve um port real e, na PRIMEIRA chamada, executa um
  ;; escritor CONCORRENTE que COMMITA a versao 1 diretamente (bypassando `gerar-folha!`), antes de delegar
  ;; ao serializador de verdade. Isto acontece DEPOIS que o nosso codigo ja' leu max=0 e propos versao=1 —
  ;; exatamente a janela de corrida que D7 aceita como custo (uma renderizacao desperdicada).
  (let [ente (random-uuid) leg (casa! ente)
        _v1 (vereador-com-mandato! ente leg "Ana")
        sid (abrir-e-encerrar! ente)
        a (ator ente)
        outro-ator-id (random-uuid)
        ja-colidiu? (atom false)
        competidor! (fn []
                      (repo-sessoes/inserir-folha! *repo-s* ente
                        {:id (random-uuid) :sessao-id sid :versao 1 :spec-versao "folha-sessao-v1"
                         :html-hash "sha256:aa" :html-content-type "text/html; charset=utf-8"
                         :html-objeto-store-ref "folhas/concorrente.html"
                         :pdf-hash "sha256:bb" :pdf-content-type "application/pdf"
                         :pdf-objeto-store-ref "folhas/concorrente.pdf"
                         :gerada-por outro-ator-id :gerada-em (Instant/now)}))
        real (ser-folha/serializador-folha-html)
        espiao (reify ser-folha/SerializadorFolha
                 (serializar [_ documento] (ser-folha/serializar real documento))
                 (serializar [_ documento versao]
                   (when (compare-and-set! ja-colidiu? false true)
                     (competidor!))
                   (ser-folha/serializar real documento versao)))
        row (controllers/gerar-folha! *repo-s* (roster-seam) (dados-da-casa-seam) a sid
              (tempo/relogio-fixo (Instant/now))
              (assoc (m-ports) :serializador espiao))
        html (String. ^bytes (os/obter (:objeto-store *sys*) (:html-objeto-store-ref row)) "UTF-8")
        todas (repo-sessoes/folhas-da-sessao *repo-s* ente sid)]
    (is (true? @ja-colidiu?) "o escritor concorrente TEM de ter rodado — sem isso este teste nao e' cobertura")
    (is (= 2 (:versao row))
        "a corrida foi PERDIDA no numero 1 (23505) — o retry rele o max e re-propoe a versao 2")
    (is (str/includes? html "versão 2 deste congelamento")
        "a PERDEDORA re-renderiza com o numero NOVO — nao guarda os bytes que imprimiam '1'")
    (is (= 2 (count todas)) "duas linhas ao todo: a do concorrente (v1) + a nossa (v2) — nenhuma duplicada")
    (is (= #{1 2} (into #{} (map :versao) todas)) "sem versao repetida")))

;; ---------- D6: sessao NAO fechada -> LANCA (herdado de `folha-da-sessao`, nao duplicado aqui) ----------

(deftest sessao-aberta-lanca-antes-de-qualquer-renderizacao
  (let [ente (random-uuid) leg (casa! ente)
        _v1 (vereador-com-mandato! ente leg "Ana")
        sid (repo-sessoes/transacao *repo-s* ente
              (fn [tx]
                (let [{sid :id} (sessao/agendar! tx {:id (random-uuid) :ente-id ente
                                                     :sessao-legislativa-id (random-uuid)
                                                     :tipo-sessao "ordinaria" :agendada-para (Instant/now)})]
                  (sessao/transicionar! tx {:id sid :ente-id ente :para "aberta" :lock-version 0})
                  sid)))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"so' sessao FECHADA tem folha"
          (gerar! sid (ator ente))))
    (is (empty? (repo-sessoes/folhas-da-sessao *repo-s* ente sid)) "nada foi materializado (fail-closed)")))

;; ---------- D8: documento invalido -> LANCA ANTES de renderizar ----------

(deftest documento-invalido-contra-o-malli-lanca-antes-de-renderizar
  ;; `dados-da-casa` devolvendo {} viola `CabecalhoDaCasa` (mapa `:closed`, todas as chaves obrigatorias
  ;; ainda que `:maybe`) — `FolhaDocumento` reprova o documento composto, e a checagem tem de disparar ANTES
  ;; de qualquer chamada ao serializador/renderizador (o espiao abaixo prova isso: se fosse chamado, o teste
  ;; capturaria e falharia a asserção de `@chamado?`).
  (let [ente (random-uuid) leg (casa! ente)
        _v1 (vereador-com-mandato! ente leg "Ana")
        sid (abrir-e-encerrar! ente)
        dados-da-casa-quebrado (fn [_ente-id _data] {})
        chamado? (atom false)
        real (ser-folha/serializador-folha-html)
        espiao (reify ser-folha/SerializadorFolha
                 (serializar [_ documento] (reset! chamado? true) (ser-folha/serializar real documento))
                 (serializar [_ documento versao] (reset! chamado? true) (ser-folha/serializar real documento versao)))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nao bate FolhaDocumento"
          (controllers/gerar-folha! *repo-s* (roster-seam) dados-da-casa-quebrado (ator ente) sid
            (tempo/relogio-fixo (Instant/now)) (assoc (m-ports) :serializador espiao))))
    (is (false? @chamado?) "D8 recusa ANTES de tocar o serializador — nenhum byte chega a ser produzido")
    (is (empty? (repo-sessoes/folhas-da-sessao *repo-s* ente sid)) "nada foi materializado")))

(deftest a-recusa-de-d8-nao-carrega-o-motivo-da-justificativa-na-ex-data
  ;; REGRESSAO (revisao adversarial da fatia 4, achado MENOR): `m/explain` devolve o DOCUMENTO INTEIRO em
  ;; `:value` (e o sub-valor de cada erro). O documento carrega `motivo` de justificativa — dado
  ;; potencialmente de saude (LGPD). Poe-lo na `ex-data` faz a excecao viajar com o dado sensivel dentro,
  ;; onde qualquer mudanca futura de observabilidade (log estruturado, APM, um `pr-str` de debug) o reabre
  ;; sem ninguem tocar neste arquivo. A ex-data deve dizer ONDE falhou, nunca O QUE havia la' dentro.
  (let [ente (random-uuid) leg (casa! ente)
        _v1 (vereador-com-mandato! ente leg "Ana")
        v2 (vereador-com-mandato! ente leg "Bruno")
        sid (abrir-e-encerrar! ente)
        a (ator ente)
        motivo-sensivel "hipertensao arterial CID I10"
        _ (repo-sessoes/criar-justificativa! *repo-s* ente
            {:id (random-uuid) :ente-id ente :sessao-id sid :vereador-id v2
             :motivo motivo-sensivel :created-by (:identidade-id a)})
        ;; `dados-da-casa` vazio reprova `CabecalhoDaCasa` — o MESMO gatilho de D8 do teste acima.
        ex (try (controllers/gerar-folha! *repo-s* (roster-seam) (fn [_ _] {}) a sid
                  (tempo/relogio-fixo (Instant/now)) (m-ports))
                nil
                (catch clojure.lang.ExceptionInfo e e))
        impresso (pr-str (ex-data ex))]
    (is (some? ex) "D8 tem de reprovar este documento — sem a recusa o teste nao e' cobertura")
    (is (not (str/includes? impresso motivo-sensivel))
        "o motivo da justificativa NAO pode viajar dentro da ex-data da recusa")
    (is (not (str/includes? impresso "Bruno"))
        "nem o nome do vereador que se justificou — a ex-data nao e' um dump do documento")
    (is (str/includes? impresso ":cabecalho-da-casa")
        "mas o diagnostico continua acionavel: o CAMINHO que falhou aparece")))

;; ---------- D9: dedup de 30s — o MESMO ator em <30s devolve a MESMA versao ----------

(deftest dedup-mesmo-ator-em-menos-de-30s-devolve-a-mesma-versao
  (let [ente (random-uuid) leg (casa! ente)
        _v1 (vereador-com-mandato! ente leg "Ana")
        sid (abrir-e-encerrar! ente)
        a (ator ente)
        agora (Instant/now)
        relogio (tempo/relogio-fixo agora)
        gerar-fixo #(controllers/gerar-folha! *repo-s* (roster-seam) (dados-da-casa-seam) a sid relogio (m-ports))
        row1 (gerar-fixo)
        row2 (gerar-fixo)]
    (is (= 1 (:versao row1)))
    (is (nil? (:ja-congelada row1)) "a PRIMEIRA geracao nao e' dedup — congelou de verdade")
    (is (= (:id row1) (:id row2)) "o REENVIO devolve a MESMA linha, nao uma nova")
    (is (= 1 (:versao row2)) "nenhuma versao 2 foi criada")
    (is (true? (:ja-congelada row2)) "o reenvio e' marcado como dedup, distinguivel de uma geracao nova")
    (is (= 1 (count (repo-sessoes/folhas-da-sessao *repo-s* ente sid))) "so' UMA linha no acervo")))

(deftest dedup-vale-tambem-sob-concorrencia-do-mesmo-ator
  ;; REGRESSAO (revisao adversarial da fatia 4, achado MAJOR): o dedup de D9 era um check-then-act FORA de
  ;; qualquer tx compartilhada — `folha-recente-do-ator` numa tx propria, a RENDERIZACAO (HTML+PDF, I/O lento)
  ;; no meio, e o INSERT numa tx nova. O duplo-clique CONCORRENTE do MESMO ator (o cenario que D9 nomeia como
  ;; motivacao) atravessava a janela: os dois pedidos liam nil, os dois renderizavam, o `UNIQUE` resolvia o
  ;; NUMERO (v1 e v2) — e o acervo ficava com DUAS linhas imutaveis do MESMO clique, que e' exatamente o que
  ;; D9 existe para impedir. Interleaving DETERMINISTICO pelo mesmo espiao da corrida acima, so' que o
  ;; competidor commita com a identidade do PROPRIO ator: ele representa o pedido gemeo que ganhou a corrida
  ;; enquanto este ainda renderizava.
  (let [ente (random-uuid) leg (casa! ente)
        _v1 (vereador-com-mandato! ente leg "Ana")
        sid (abrir-e-encerrar! ente)
        a (ator ente)
        agora (Instant/now)
        ja-colidiu? (atom false)
        gemeo! (fn []
                 (repo-sessoes/inserir-folha! *repo-s* ente
                   {:id (random-uuid) :sessao-id sid :versao 1 :spec-versao "folha-sessao-v1"
                    :html-hash "sha256:aa" :html-content-type "text/html; charset=utf-8"
                    :html-objeto-store-ref "folhas/gemeo.html"
                    :pdf-hash "sha256:bb" :pdf-content-type "application/pdf"
                    :pdf-objeto-store-ref "folhas/gemeo.pdf"
                    ;; a MESMA identidade: e' o duplo-clique, nao uma segunda Secretaria.
                    :gerada-por (:identidade-id a) :gerada-em agora}))
        real (ser-folha/serializador-folha-html)
        espiao (reify ser-folha/SerializadorFolha
                 (serializar [_ documento] (ser-folha/serializar real documento))
                 (serializar [_ documento versao]
                   (when (compare-and-set! ja-colidiu? false true) (gemeo!))
                   (ser-folha/serializar real documento versao)))
        row (controllers/gerar-folha! *repo-s* (roster-seam) (dados-da-casa-seam) a sid
              (tempo/relogio-fixo agora) (assoc (m-ports) :serializador espiao))
        todas (repo-sessoes/folhas-da-sessao *repo-s* ente sid)]
    (is (true? @ja-colidiu?) "o pedido gemeo TEM de ter rodado — sem isso este teste nao e' cobertura")
    (is (= 1 (count todas))
        "D9 sob concorrencia: UMA linha no acervo, nao duas — o gemeo do mesmo ator nao cria versao nova")
    (is (= 1 (:versao row)) "devolve a versao que o gemeo congelou, nao uma v2")
    (is (true? (:ja-congelada row)) "e a marca dedup, para a borda responder 200 e nao 201")
    (is (nil? (os/obter (:objeto-store *sys*) "folhas/gemeo.html"))
        "o caminho de dedup NAO guarda binario: gravar os bytes re-renderizados (que imprimem outro numero)
         sob o ref da linha existente CORROMPERIA o hash ja' gravado")))

(deftest dedup-e-por-ator-dois-atores-diferentes-produzem-duas-versoes
  (let [ente (random-uuid) leg (casa! ente)
        _v1 (vereador-com-mandato! ente leg "Ana")
        sid (abrir-e-encerrar! ente)
        row1 (gerar! sid (ator ente))
        row2 (gerar! sid (ator ente))]
    (is (= 1 (:versao row1)))
    (is (= 2 (:versao row2)) "ator DIFERENTE -> dedup nao se aplica, congela versao nova")))

;; ---------- a chave do objeto_store so' usa uuid ECHOADO pelo banco (disciplina do molde) ----------

(deftest store-ref-usa-o-uuid-echoado-pelo-banco-nao-o-parametro-cru
  ;; GUARDA (revisao adversarial da fatia 4, achado MENOR): o molde
  ;; (`legislativo/gerar-artefato-publicacao!`) monta a chave do objeto_store a partir do `(:id norma)`
  ;; ECHOADO pela leitura, nunca do parametro cru do caller — defesa em profundidade para que a chave so'
  ;; contenha valores round-tripados e tipados pelo PG (uuid canonico), independentemente do que a borda
  ;; (Fatia 5, ainda nao escrita) coagir. Nao ha' exploracao hoje; este teste amarra a disciplina.
  (let [ente (random-uuid) leg (casa! ente)
        _v1 (vereador-com-mandato! ente leg "Ana")
        sid (abrir-e-encerrar! ente)
        row (gerar! sid (ator ente))
        sessao-real (repo-sessoes/buscar-sessao *repo-s* ente sid)]
    (is (= (str "folhas/" ente "/" (:id sessao-real) "/" (:html-hash row) ".html")
           (:html-objeto-store-ref row)))
    (is (= (str "folhas/" ente "/" (:id sessao-real) "/" (:pdf-hash row) ".pdf")
           (:pdf-objeto-store-ref row)))))

;; ---------- multi-tenant: ator de outra Casa nao alcanca a folha (RLS via folha-da-sessao) ----------

(deftest ator-de-outra-casa-nao-alcanca-a-folha
  (let [ente-a (random-uuid) ente-b (random-uuid)
        leg (casa! ente-a)
        _v1 (vereador-com-mandato! ente-a leg "Ana")
        sid (abrir-e-encerrar! ente-a)]
    (is (nil? (controllers/gerar-folha! *repo-s* (roster-seam) (dados-da-casa-seam) (ator ente-b) sid
                (tempo/relogio-fixo (Instant/now)) (m-ports)))
        "sessao de outra Casa: RLS ja' a esconde -> nil de folha-da-sessao -> nil aqui, nunca vaza")
    (is (empty? (repo-sessoes/folhas-da-sessao *repo-s* ente-a sid))
        "e nada foi congelado no tenant DONO por essa tentativa")))

;; ---------- a tabela recusa UPDATE e DELETE (trigger de imutabilidade, mig 0073) ----------

(deftest tabela-folha-sessao-e-append-only
  (let [ente (random-uuid) leg (casa! ente)
        _v1 (vereador-com-mandato! ente leg "Ana")
        sid (abrir-e-encerrar! ente)
        row (gerar! sid (ator ente))
        ds (:ds (:datasource *sys*))]
    (tenancy/com-tenant* ds ente
      (fn [tx]
        (is (thrown? Exception
              (jdbc/execute-one! tx
                (sql/format {:update :sessoes.folha_sessao :set {:versao 99}
                             :where [:and [:= :ente_id ente] [:= :id (:id row)]]})))
            "UPDATE e' barrado (append-only: a folha congelada e' imutavel)")))
    (tenancy/com-tenant* ds ente
      (fn [tx]
        (is (thrown? Exception
              (jdbc/execute-one! tx
                (sql/format {:delete-from :sessoes.folha_sessao
                             :where [:and [:= :ente_id ente] [:= :id (:id row)]]})))
            "DELETE e' barrado (append-only)")))))

;; ---------- exige os TRES ports (nil-guard, forma do molde `gerar-artefato-publicacao!`) ----------

(deftest exige-os-tres-ports
  (let [ente (random-uuid) leg (casa! ente)
        _v1 (vereador-com-mandato! ente leg "Ana")
        sid (abrir-e-encerrar! ente)
        a (ator ente)
        relogio (tempo/relogio-fixo (Instant/now))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"objeto-store ausente"
          (controllers/gerar-folha! *repo-s* (roster-seam) (dados-da-casa-seam) a sid relogio
            (dissoc (m-ports) :objeto-store))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"serializador ausente"
          (controllers/gerar-folha! *repo-s* (roster-seam) (dados-da-casa-seam) a sid relogio
            (dissoc (m-ports) :serializador))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"renderizador-pdf ausente"
          (controllers/gerar-folha! *repo-s* (roster-seam) (dados-da-casa-seam) a sid relogio
            (dissoc (m-ports) :renderizador-pdf))))))
