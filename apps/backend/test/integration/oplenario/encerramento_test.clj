(ns oplenario.encerramento-test
  "INTEGRACAO (PG real): o PLANO DE DADOS do encerramento de uma Casa (ADR-0018, fatia 2, Eixo 4).

  Duas Casas (A e B) semeadas em varios modulos — inclusive tabelas append-only/imutaveis (trilha de auditoria, selo do
  dia, protocolo geral), uma linha em STAGING de importacao, tabelas de tenant sem RLS (sessao, outbox) e uma FILHA sem
  `ente_id` (schema de teste, FK para `compliance.remessa_gerada`) com uma NETA. A aplicacao conecta como
  `oplenario_pool` (o role de runtime, NOBYPASSRLS, nao-dono) — o superuser so' semeia e confere.

  Prova:
  - a EXPORTACAO de A tem as linhas de A e NADA de B (o id de B nao aparece em byte nenhum do ZIP); o manifesto bate com
    os bytes e o sha256 reais; o dicionario cobre as tabelas exportadas; tabelas sem RLS e o staging ficam fora;
  - a funcao SQL RECUSA sem pedido aprovado, com o mesmo operador nas duas pontas, sem exportacao confirmada, com a
    confirmacao ha' menos de 90 dias, com a Casa ativa — e o role da aplicacao dentro do tenant nem a executa;
  - com tudo certo, ZERO linhas de A em toda tabela com `ente_id` (varredura generica do catalogo), as contagens de B
    IDENTICAS, a pessoa so' de A apagada e a compartilhada mantida, os blobs de A fora e os de B intactos, os triggers e
    o FORCE RLS como estavam;
  - a RETOMADA: IdP e satelite fora do ar na 1a chamada -> pendentes; a 2a termina sem erro."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [oplenario.auditoria.components.repositorio :as repo-auditoria]
            [oplenario.config :as config]
            [oplenario.encerramento :as encerramento]
            [oplenario.integracao-ia.diplomat.http.out :as plataforma-ia]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp :as idp]
            [oplenario.kernel.components.objeto-store :as store]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao])
  (:import (java.io ByteArrayInputStream)
           (java.security MessageDigest)
           (java.util HexFormat)
           (java.util.zip ZipInputStream)))

(def ^:dynamic *dono* nil)   ; superuser (o dono das tabelas): so' semeia e confere
(def ^:dynamic *pool* nil)   ; o role de runtime da aplicacao (oplenario_pool)

(def ^:private opts {:builder-fn rs/as-unqualified-maps})

(defn- sql! [& args] (jdbc/execute! *dono* (vec args) opts))

(defn- sql-cenario!
  "Monta um cenario que o banco recusaria pela via normal (ex.: desfazer uma confirmacao de recebimento, que um trigger
  impede): como dono, com os triggers de usuario desligados SO' nesta transacao."
  [& args]
  (jdbc/with-transaction [tx *dono*]
    (jdbc/execute! tx ["SET LOCAL session_replication_role = replica"])
    (jdbc/execute! tx (vec args) opts)))

(defn- criar-filhas-de-teste!
  "Uma FILHA sem `ente_id` (FK para remessa_gerada, que tem PK de uma coluna) e uma NETA — o inventario tem de acha-las
  pela FK. Com grants ao app, como uma tabela de modulo teria."
  [ds]
  (doseq [s ["CREATE SCHEMA IF NOT EXISTS teste_encerramento"
             "CREATE TABLE IF NOT EXISTS teste_encerramento.anexo_remessa (
                id uuid PRIMARY KEY, remessa_id uuid NOT NULL REFERENCES compliance.remessa_gerada (id), nota text)"
             "CREATE TABLE IF NOT EXISTS teste_encerramento.anexo_nota (
                id uuid PRIMARY KEY, anexo_id uuid NOT NULL REFERENCES teste_encerramento.anexo_remessa (id), texto text)"
             "GRANT USAGE ON SCHEMA teste_encerramento TO oplenario_app"
             "GRANT SELECT, INSERT ON teste_encerramento.anexo_remessa, teste_encerramento.anexo_nota TO oplenario_app"]]
    (jdbc/execute! ds [s])))

(def ^:private criados
  "As Casas e os operadores que cada teste pos no registro supratenant — tirados depois de cada teste, para o console
  das outras suites (que lista TODAS as Casas e a fila de pedidos) nao ver Casas encerradas e pedidos `apagar` de
  teste."
  (atom {:entes #{} :operadores #{}}))

(defn- limpar-registro! [ds]
  (let [{:keys [entes operadores]} @criados
        uuids #(into-array java.util.UUID %)]
    (when (seq entes)
      ;; a Casa ENCERRADA e' imutavel por trigger (a linha do registro e' a prova); a limpeza do TESTE roda como dono
      ;; com os triggers de usuario desligados SO' nesta transacao
      (jdbc/with-transaction [tx ds]
        (jdbc/execute! tx ["SET LOCAL session_replication_role = replica"])
        (doseq [t ["admin_sistema.pedido_restricao" "admin_sistema.exportacao_casa" "admin_sistema.ente"]]
          (jdbc/execute! tx [(str "DELETE FROM " t " WHERE ente_id = ANY (?)") (uuids entes)]))))
    (when (seq operadores)
      (jdbc/execute! ds ["DELETE FROM admin_sistema.operador WHERE id = ANY (?)" (uuids operadores)]))
    (reset! criados {:entes #{} :operadores #{}})))

(use-fixtures :each (fn [t] (try (t) (finally (limpar-registro! *dono*)))))

(use-fixtures :once
  (fn [t]
    (let [cfg (config/carregar)
          dono (component/start (datasource/datasource cfg))
          pool (component/start (datasource/datasource (update cfg :db assoc :user "oplenario_pool"
                                                               :password "oplenario_dev_pool")))]
      (try
        (migracao/migrar! (:ds dono))
        (criar-filhas-de-teste! (:ds dono))
        (binding [*dono* (:ds dono) *pool* (:ds pool)] (t))
        (finally
          (jdbc/execute! (:ds dono) ["DROP SCHEMA IF EXISTS teste_encerramento CASCADE"])
          (component/stop pool)
          (component/stop dono))))))

;; ---------------------------------------------------------------------------------------------
;; object store em memoria (com `listar`, recursivo ou nao)
;; ---------------------------------------------------------------------------------------------

(defrecord StoreMemoria [m]
  store/ObjetoStore
  (guardar! [_ k b _] (swap! m assoc k b) k)
  (guardar-stream! [_ k in _] (swap! m assoc k (.readAllBytes ^java.io.InputStream in)) k)
  (obter [_ k] (get @m k))
  (abrir [_ k] (some-> (get @m k) ByteArrayInputStream.))
  (remover! [_ k] (swap! m dissoc k) k)
  (listar [_ prefixo recursivo?]
    (->> (keys @m)
         (filter #(str/starts-with? % prefixo))
         (map (fn [k] (let [resto (subs k (count prefixo))]
                        (if (and (not recursivo?) (str/includes? resto "/"))
                          (str prefixo (first (str/split resto #"/")) "/")
                          k))))
         distinct sort vec)))

(defn- store-memoria [] (->StoreMemoria (atom {})))

;; ---------------------------------------------------------------------------------------------
;; semeadura: uma Casa com dado em varios modulos
;; ---------------------------------------------------------------------------------------------

(defn- operador! [nome]
  (let [id (random-uuid)]
    (swap! criados update :operadores conj id)
    (sql! "INSERT INTO admin_sistema.operador (id, email, nome) VALUES (?, ?, ?)" id (str "op-" id "@oplenario.dev") nome)
    id))

(defn- semear-casa!
  "A Casa `ente` com dado em cadastros, identidade, legislativo, compliance, sessoes, integracao_ia, auditoria, normas,
  shared e na filha de teste; blobs no `st`. `compartilhada` = uma pessoa com vinculo tambem em outra Casa."
  [ente marca st repo-aud compartilhada]
  (let [pessoa (random-uuid) remessa (random-uuid) anexo (random-uuid) seg (random-uuid)
        pedido (random-uuid) anexo-atendimento (random-uuid)
        chave-remessa (str "remessas/" ente "/remessa_mensal_sim/2026-09/" marca ".bin")
        chave-gravacao (str "gravacao/" ente "/" seg)
        chave-anexo (str "atendimento/" ente "/" pedido "/" anexo-atendimento)]
    (swap! criados update :entes conj ente)
    (sql! "INSERT INTO cadastros.municipios (codigo_ibge, nome, uf, capital, populacao)
           VALUES ('2302008', 'Baturité', 'CE', false, 35000) ON CONFLICT DO NOTHING")
    (sql! "INSERT INTO admin_sistema.ente (ente_id, nome, uf, estado) VALUES (?, ?, 'CE', 'ativo')" ente (str "Câmara " marca))
    (sql! "INSERT INTO cadastros.ente (ente_id, municipio_ibge, nome_oficial) VALUES (?, '2302008', ?)" ente (str "Câmara " marca))
    (sql! "INSERT INTO cadastros.legislatura (id, ente_id, numero, ano_inicio, ano_fim, vigente, efetivado_em)
           VALUES (?, ?, 19, 2025, 2028, true, now())" (random-uuid) ente)
    (sql! "INSERT INTO cadastros.vereador (id, ente_id, nome, efetivado_em) VALUES (?, ?, ?, now())"
          (random-uuid) ente (str "Vereadora " marca " \"Aspas\", vírgula\nquebra"))
    (sql! "INSERT INTO identidade.identidade (id, cpf, nome) VALUES (?, ?, ?)" pessoa (str "1111111" (rand-int 9999)) (str "Pessoa só da " marca))
    (sql! "INSERT INTO identidade.identidade_externa (id, identidade_id, provedor, sub) VALUES (?, ?, 'govbr', ?)"
          (random-uuid) pessoa (str "sub-" marca "-" pessoa))
    (doseq [p [pessoa compartilhada]]
      (sql! "INSERT INTO identidade.vinculo (id, ente_id, identidade_id, tipo) VALUES (?, ?, ?, 'servidor')" (random-uuid) ente p)
      (sql! "INSERT INTO identidade.usuario_papel (id, ente_id, identidade_id, papel) VALUES (?, ?, ?, 'secretario')"
            (random-uuid) ente p))
    (sql! "INSERT INTO identidade.sessao (sessao_hash, identidade_id, ente_id, expira_em, ocioso_ate)
           VALUES (?, ?, ?, now() + interval '1 hour', now() + interval '1 hour')" (.getBytes (str "segredo-" marca "-" pessoa)) pessoa ente)
    (sql! "INSERT INTO legislativo.protocolo_geral (id, ente_id, numero, ano, objeto_tipo, sentido, assunto, efetivado_em)
           VALUES (?, ?, 1, 2026, 'oficio_recebido', 'recebido', ?, now())" (random-uuid) ente (str "Ofício da " marca))
    (sql! "INSERT INTO compliance.remessa_gerada (id, ente_id, template_chave, sistema, competencia, spec_layout_versao,
           registry_versao_ref, hash, objeto_store_ref) VALUES (?, ?, 'remessa_mensal_sim', 'SIM', '2026-09', 'v1', 'r1', ?, ?)"
          remessa ente marca chave-remessa)
    (sql! "INSERT INTO teste_encerramento.anexo_remessa (id, remessa_id, nota) VALUES (?, ?, ?)" anexo remessa (str "anexo " marca))
    (sql! "INSERT INTO teste_encerramento.anexo_nota (id, anexo_id, texto) VALUES (?, ?, ?)" (random-uuid) anexo (str "nota " marca))
    (sql! "INSERT INTO sessoes.gravacao_segmento (id, ente_id, iniciou_em, motivo_inicio, container_bruto_uri, fonte_ingestao,
           efetivado_em) VALUES (?, ?, now(), 'inicio_sessao', ?, 'gravacao_local_pos_sessao', now())" seg ente chave-gravacao)
    ;; STAGING de importacao (lote nao efetivado): invisivel ate' a reconciliacao — nao exporta, mas o apagamento leva
    (sql! "INSERT INTO sessoes.gravacao_segmento (id, ente_id, iniciou_em, motivo_inicio, container_bruto_uri, fonte_ingestao,
           lote_id) VALUES (?, ?, now(), 'inicio_sessao', ?, 'importacao_legado', ?)"
          (random-uuid) ente (str "gravacao/" ente "/staging") (random-uuid))
    ;; o anexo da resposta a um pedido de e-SIC (participacao.anexo): tabela com ente_id e RLS entra no inventario sozinha; o
    ;; blob `atendimento/<ente>/<protocolo>/<anexo>` e' achado pela convencao (a coluna `chave_objeto` nao e' ponteiro)
    (sql! "INSERT INTO participacao.anexo (ente_id, id, objeto_tipo, objeto_id, origem, nome, tipo_midia, bytes, sha256,
           chave_objeto, enviado_por) VALUES (?, ?, 'pedido_esic', ?, 'casa', ?, 'application/pdf', 10, ?, ?, ?)"
          ente anexo-atendimento pedido (str "folha-" marca ".pdf") (apply str (repeat 64 "a")) chave-anexo pessoa)
    (sql! "INSERT INTO integracao_ia.orcamento_ia (id, ente_id, moeda, definido_por) VALUES (?, ?, 'BRL', 'operador')" (random-uuid) ente)
    (sql! "INSERT INTO auditoria.selo_diario (ente_id, dia, seq, selo) VALUES (?, '2026-09-30', 1, ?)" ente (str "selo-" marca))
    (sql! "INSERT INTO normas.norma (id, ente_id, camada, especie, titulo) VALUES (?, ?, 'casa', 'regimento_interno', ?)"
          (random-uuid) ente (str "Regimento da " marca))
    (sql! "INSERT INTO shared.outbox (ente_id, tipo, payload, idempotency_key) VALUES
           (?, 'legislativo.proposicao.protocolada', ?::jsonb, ?), (?, 'admin_sistema.casa.suspensa', '{}'::jsonb, ?)"
          ente (json/write-value-as-string {:marca marca}) (str (random-uuid)) ente (str (random-uuid)))
    (doseq [acao ["legislativo/protocolar" "identidade/entrar"]]
      (repo-auditoria/registrar! repo-aud {:ente-id ente :ator-tipo "pessoa" :identidade-id pessoa :papeis ["secretario"]
                                           :acao acao :classe "escrita" :decisao "permitido" :status-http 200
                                           :canal "web" :ip "10.0.0.1" :detalhe {:marca marca}}))
    (doseq [k [chave-remessa chave-gravacao (str "folhas/" ente "/sessao/folha.pdf") chave-anexo]]
      (store/guardar! st k (.getBytes (str "conteudo " marca " " k)) "application/octet-stream"))
    ;; a CIDADA (so' vinculo de cidadao): segue uma materia e faz uma manifestacao ANONIMA a ouvidoria
    (let [cidada (random-uuid)]
      (sql! "INSERT INTO identidade.identidade (id, cpf, nome) VALUES (?, ?, ?)" cidada (str "3333" (rand-int 99999))
            (str "Cidadã da " marca))
      (sql! "INSERT INTO identidade.vinculo (id, ente_id, identidade_id, tipo) VALUES (?, ?, ?, 'cidadao')" (random-uuid) ente cidada)
      (sql! "INSERT INTO transparencia.acompanhamento (ente_id, proposicao_id, seguidor_identidade_id, created_by, efetivado_em)
             VALUES (?, ?, ?, ?, now())" ente (random-uuid) cidada cidada)
      ;; o anexo que a CIDADA juntou ao proprio pedido (origem `requerente`): `enviado_por` e' o id de quem so' e' cidada
      (let [protocolo (random-uuid) anexo (random-uuid) chave (str "atendimento/" ente "/" protocolo "/" anexo)]
        (sql! "INSERT INTO participacao.anexo (ente_id, id, objeto_tipo, objeto_id, origem, nome, tipo_midia, bytes, sha256,
               chave_objeto, enviado_por) VALUES (?, ?, 'pedido_esic', ?, 'requerente', ?, 'application/pdf', 10, ?, ?, ?)"
              ente anexo protocolo (str "contrato-" marca ".pdf") (apply str (repeat 64 "b")) chave cidada)
        (store/guardar! st chave (.getBytes (str "conteudo da cidada " marca)) "application/pdf"))
      (sql! "INSERT INTO participacao.manifestacao_ouvidoria (ente_id, ano, sequencial, protocolo, tipo, assunto, descricao,
             anonima, recibo_em, efetivado_em) VALUES (?, 2026, 1, 'OUV-2026-000001', 'denuncia', 'Denúncia anônima',
             'Relato.', true, now(), now())" ente)
      (doseq [[quem acao] [[cidada "transparencia/seguir"] [cidada "participacao/protocolar-manifestacao"]
                           ;; a servidora tambem pode agir COMO cidada (sessao do gov.br): ali ela e' cidada
                           [pessoa "participacao/protocolar-manifestacao"]]]
        (repo-auditoria/registrar! repo-aud {:ente-id ente :ator-tipo "cidadao" :identidade-id quem :papeis []
                                             :acao acao :classe "escrita" :decisao "permitido" :status-http 200
                                             :canal "web" :ip "200.1.2.3" :detalhe {:marca marca :quem (str quem)}}))
      {:pessoa pessoa :cidada cidada})))

(defn- liberar-apagamento!
  "Tudo o que o Eixo 4.5 exige: Casa suspensa com encerramento em curso, exportacao pronta confirmada ha' `dias`, e o
  pedido `apagar` aprovado por OUTRO operador. Devolve {:pedido :exportacao :sha256}."
  ([ente] (liberar-apagamento! ente 91))
  ([ente dias]
   (let [ana (operador! "Ana") beto (operador! "Beto") pedido (random-uuid) exp (random-uuid)
         sha (apply str (repeat 64 "a"))]
     (sql! "UPDATE admin_sistema.ente SET estado = 'suspenso', motivo_restricao = 'encerramento_em_curso',
            restrita_desde = now() WHERE ente_id = ?" ente)
     (sql! "INSERT INTO admin_sistema.exportacao_casa (id, ente_id, solicitada_por_tipo, solicitada_por, estado, chave_objeto,
            sha256, bytes, concluida_em, confirmada_em, confirmada_por_tipo, confirmada_por)
            VALUES (?, ?, 'operador', ?, 'pronta', ?, ?, 10, now() - make_interval(days => ?::int + 1), now() - make_interval(days => ?::int),
                    'admin_ente', ?)"
           exp ente ana (str "exportacoes/" ente "/" exp ".zip") sha dias dias (random-uuid))
     (sql! "INSERT INTO admin_sistema.pedido_restricao (id, ente_id, acao, motivo, justificativa, estado, pedido_por,
            decidido_por, decidido_em) VALUES (?, ?, 'apagar', 'fim_da_guarda', 'Fim da guarda de 90 dias.', 'aprovado', ?, ?, now())"
           pedido ente ana beto)
     {:pedido pedido :exportacao exp :sha256 sha})))

(defn- repo-aud [] (assoc (repo-auditoria/repositorio) :datasource {:ds *pool*}))

(defn- duas-casas!
  "A e B semeadas, com uma pessoa compartilhada, num store novo."
  []
  (let [st (store-memoria) a (random-uuid) b (random-uuid) comp (random-uuid) r (repo-aud)]
    (sql! "INSERT INTO identidade.identidade (id, cpf, nome) VALUES (?, ?, 'Pessoa das duas Casas')" comp (str "2222" (rand-int 99999)))
    {:st st :a a :b b :compartilhada comp
     :pa (semear-casa! a "Casa-A" st r comp)
     :pb (semear-casa! b "Casa-B" st r comp)}))

;; ---------------------------------------------------------------------------------------------
;; leitura do ZIP
;; ---------------------------------------------------------------------------------------------

(defn- hex [^bytes b] (.formatHex (HexFormat/of) b))
(defn- sha256 [^bytes b] (hex (.digest (MessageDigest/getInstance "SHA-256") b)))

(defn- entradas-do-zip [^bytes zip]
  (with-open [z (ZipInputStream. (ByteArrayInputStream. zip))]
    (loop [acc (sorted-map)]
      (if-let [e (.getNextEntry z)]
        (recur (assoc acc (.getName e) (.readAllBytes z)))
        acc))))

(defn- texto [^bytes b] (String. b "UTF-8"))

(defn- ler-csv
  "RFC 4180 (o que a exportacao promete no LEIA-ME): -> vetor de linhas, cada uma vetor de campos (nil = NULL)."
  [^String s]
  (loop [i 0 campo (StringBuilder.) aspas? false citado? false linha [] linhas []]
    (if (>= i (count s))
      (cond-> linhas (or (seq linha) (pos? (.length campo))) (conj (conj linha (str campo))))
      (let [c (.charAt s i)]
        (cond
          aspas? (cond (and (= c \") (< (inc i) (count s)) (= \" (.charAt s (inc i))))
                       (recur (+ i 2) (.append campo \") true true linha linhas)
                       (= c \") (recur (inc i) campo false true linha linhas)
                       :else (recur (inc i) (.append campo c) true true linha linhas))
          (= c \") (recur (inc i) campo true true linha linhas)
          (= c \,) (recur (inc i) (StringBuilder.) false false
                          (conj linha (if (and (zero? (.length campo)) (not citado?)) nil (str campo))) linhas)
          (= c \return) (recur (inc i) campo false citado? linha linhas)
          (= c \newline) (recur (inc i) (StringBuilder.) false false []
                                (conj linhas (conj linha (if (and (zero? (.length campo)) (not citado?)) nil (str campo)))))
          :else (recur (inc i) (.append campo c) false citado? linha linhas))))))

(defn- seams [] (encerramento/seams-de-auditoria (repo-aud)))

;; ---------------------------------------------------------------------------------------------
;; EXPORTACAO
;; ---------------------------------------------------------------------------------------------

(deftest exportacao-de-a-tem-tudo-de-a-e-nada-de-b
  (let [{:keys [st a b compartilhada pa pb]} (duas-casas!)
        exp (random-uuid)
        r (encerramento/exportar-casa! {:ds *pool* :objeto-store st :auditoria (seams)} a exp)
        zip (get @(:m st) (:chave r))
        es (entradas-do-zip zip)
        manifesto (json/read-value (texto (es "manifesto.json")))]
    (testing "o ZIP sobe na chave do contrato, e o sha256/bytes devolvidos sao os do arquivo real"
      (is (= (str "exportacoes/" a "/" exp ".zip") (:chave r)))
      (is (= (sha256 zip) (:sha256 r)))
      (is (re-matches #"[0-9a-f]{64}" (:sha256 r)))
      (is (= (alength ^bytes zip) (:bytes r)))
      (is (= manifesto (:manifesto r)) "o manifesto devolvido e' o mesmo do ZIP"))
    (testing "o manifesto descreve cada entrada (menos ele mesmo) com bytes e sha256 reais"
      (is (= (disj (set (keys es)) "manifesto.json") (set (map #(get % "caminho") (manifesto "arquivos")))))
      (doseq [{c "caminho" n "bytes" h "sha256"} (manifesto "arquivos")]
        (is (= [n h] [(alength ^bytes (es c)) (sha256 (es c))]) c))
      (is (= {"formato" "oplenario.exportacao-da-casa" "versao" 1 "ente_id" (str a) "exportacao_id" (str exp)}
             (select-keys manifesto ["formato" "versao" "ente_id" "exportacao_id"]))))
    (testing "NADA de B: o id de B e as marcas de B nao aparecem em byte nenhum do ZIP"
      (doseq [[nome bs] es]
        (is (not (str/includes? (texto bs) (str b))) (str "o id de B vazou em " nome))
        (is (not (str/includes? (texto bs) "Casa-B")) (str "dado de B vazou em " nome)))
      (is (not-any? #(str/includes? % (str b)) (keys es))))
    (testing "as linhas de A estao la' (varios modulos, CSV escapado)"
      (is (str/includes? (texto (es "dados/legislativo/protocolo_geral.csv")) "Ofício da Casa-A"))
      (is (str/includes? (texto (es "dados/cadastros/vereador.csv")) "\"Vereadora Casa-A \"\"Aspas\"\", vírgula\nquebra\""))
      (is (str/includes? (texto (es "dados/cadastros/ente.csv")) (str a)))
      (is (str/includes? (texto (es "dados/normas/norma.csv")) "Regimento da Casa-A"))
      (is (str/includes? (texto (es "dados/participacao/anexo.csv")) "folha-Casa-A.pdf") "o anexo da resposta (participacao.anexo)")
      (is (= 2 (get-in manifesto ["tabelas" "participacao.anexo"])))
      (is (= 5 (get-in manifesto ["tabelas" "auditoria.registro"])))
      (is (= 1 (get-in manifesto ["tabelas" "teste_encerramento.anexo_remessa"])) "a filha sem ente_id, pela FK")
      (is (= 1 (get-in manifesto ["tabelas" "teste_encerramento.anexo_nota"])) "e a neta")
      (is (str/includes? (texto (es "dados/teste_encerramento/anexo_nota.csv")) "nota Casa-A")))
    (testing "o staging de importacao nao e' da Casa ainda; tabela sem RLS (segredo/infra) nao sai"
      (is (= 1 (get-in manifesto ["tabelas" "sessoes.gravacao_segmento"])))
      (is (nil? (es "dados/identidade/sessao.csv")))
      (is (nil? (es "dados/shared/outbox.csv")))
      (is (nil? (es "dados/identidade/credencial_agente.csv"))))
    (testing "as pessoas que a Casa referencia (a so' de A e a compartilhada), nunca a so' de B"
      (let [p (texto (es "dados/identidade/identidade.csv"))]
        (is (str/includes? p (str (:pessoa pa))))
        (is (str/includes? p (str compartilhada)))
        (is (not (str/includes? p (str (:pessoa pb)))))))
    (testing "o dicionario cobre exatamente as tabelas de dados/"
      (let [dic (rest (ler-csv (texto (es "dicionario.csv"))))
            do-dic (set (map #(str (nth % 0) "." (nth % 1)) dic))
            de-dados (set (keep #(when-let [[_ s t] (re-matches #"dados/([^/]+)/([^/]+)\.csv" %)] (str s "." t)) (keys es)))]
        (is (= de-dados do-dic))
        (is (< 50 (count de-dados)) "o inventario descoberto, nao uma lista curta")
        (is (some #(= ["legislativo" "protocolo_geral" "assunto" "text" "false"] (take 5 %)) dic))))
    (testing "o CSV volta ao que o banco tem: cabecalho, NULL vazio, texto com aspas/virgula/quebra"
      (let [[cab & linhas] (ler-csv (texto (es "dados/cadastros/vereador.csv")))
            l (zipmap cab (first linhas))]
        (is (= 1 (count linhas)))
        (is (= "Vereadora Casa-A \"Aspas\", vírgula\nquebra" (l "nome")))
        (is (nil? (l "identidade_id")) "NULL = campo vazio")
        (is (= (str a) (l "ente_id")))))
    (testing "os blobs de A (referenciados e por convencao), na chave original"
      (is (some? (es (str "arquivos/remessas/" a "/remessa_mensal_sim/2026-09/Casa-A.bin"))))
      (is (some? (es (str "arquivos/folhas/" a "/sessao/folha.pdf"))) "achado pela convencao, sem referencia")
      (is (some #(str/starts-with? % (str "arquivos/gravacao/" a "/")) (keys es)))
      (is (some #(str/starts-with? % (str "arquivos/atendimento/" a "/")) (keys es))
          "o blob do anexo, achado pela convencao `atendimento/<ente>/...`")
      (is (= (count (filter #(str/starts-with? % "arquivos/") (keys es)))
             (get-in manifesto ["totais" "arquivos_da_casa"]))))
    (testing "a trilha: a corrente conferida na hora (ADR-0017) e os selos do dia"
      (let [v (json/read-value (texto (es "auditoria/verificacao.json")))]
        (is (= [true 5] [(v "integra") (v "total")]))
        (is (str/includes? (v "como_conferir") "selo_anterior")))
      (is (str/includes? (texto (es "auditoria/selos-do-dia.csv")) "selo-Casa-A"))
      (is (str/includes? (texto (es "LEIA-ME.txt")) "dicionario.csv")))))

(deftest exportacao-protege-o-cidadao
  ;; Lei 13.460 art. 10 §7o + LGPD + ADR-0017 4c: quem e' so' cidadao sai pseudonimizado em TODO arquivo; o ato que
  ;; pode ser anonimo sai sem ator; servidores seguem identificados (funcao publica)
  (let [{:keys [st a pa]} (duas-casas!)
        {:keys [cidada pessoa]} pa
        pseudo-cidada (str "#" (subs (sha256 (.getBytes (str a "|" cidada) "UTF-8")) 0 12))
        r (encerramento/exportar-casa! {:ds *pool* :objeto-store st :auditoria (seams)} a (random-uuid))
        zip ^bytes (get @(:m st) (:chave r))
        es (entradas-do-zip zip)
        tudo (apply str (map texto (vals es)))
        trilha (let [[cab & ls] (ler-csv (texto (es "dados/auditoria/registro.csv")))] (mapv #(zipmap cab %) ls))]
    (testing "o id da cidada nao aparece em byte nenhum do ZIP (nem comprimido, nem em arquivo algum)"
      (is (not (str/includes? tudo (str cidada))))
      (is (not (str/includes? (str/lower-case tudo) (str/lower-case (str cidada)))))
      (is (not (str/includes? (String. zip "ISO-8859-1") (str cidada))))
      (is (not (str/includes? tudo "Cidadã da Casa-A")) "nem o nome")
      (is (nil? (es "dados/identidade/sessao.csv"))))
    (testing "no lugar, o MESMO pseudonimo da tela da trilha — inclusive dentro de JSON"
      (is (= pseudo-cidada ((:pseudonimo (seams)) a cidada)) "a funcao da tela, pelo seam")
      (let [seg (first (filter #(= "transparencia/seguir" (% "acao")) trilha))]
        (is (= pseudo-cidada (seg "identidade_id")))
        (is (nil? (seg "ip")) "ato como cidadao sai sem IP")
        (is (str/includes? (seg "detalhe") pseudo-cidada)))
      (let [[l] (rest (ler-csv (texto (es "dados/transparencia/acompanhamento.csv"))))]
        (is (some #{pseudo-cidada} l))))
    (testing "o protocolo de manifestacao (pode ter sido anonimo) sai SEM autor — mesmo o da servidora agindo como cidada"
      (let [prot (filter #(= "participacao/protocolar-manifestacao" (% "acao")) trilha)]
        (is (= 2 (count prot)))
        (is (every? #(nil? (% "identidade_id")) prot))
        (is (every? #(nil? (% "ip")) prot))))
    (testing "o anexo que a cidada juntou ao proprio pedido: a linha e o arquivo entram, e `enviado_por` sai pseudonimizado"
      (let [[cab & ls] (ler-csv (texto (es "dados/participacao/anexo.csv")))
            linhas (map #(zipmap cab %) ls)
            do-requerente (first (filter #(= "requerente" (% "origem")) linhas))
            da-casa (first (filter #(= "casa" (% "origem")) linhas))]
        (is (= 2 (count linhas)) "o da Casa e o do requerente")
        (is (= (str "contrato-Casa-A.pdf") (do-requerente "nome")))
        (is (= pseudo-cidada (do-requerente "enviado_por")) "o MESMO pseudonimo da tela da trilha, nunca o id da cidada")
        (is (= (str pessoa) (da-casa "enviado_por")) "o servidor segue identificado (funcao publica)"))
      (is (some #(and (str/starts-with? % (str "arquivos/atendimento/" a "/")) (str/includes? (texto (es %)) "conteudo da cidada"))
                (keys es))
          "o arquivo da cidada vai na exportacao entregue a Casa (e' documento que ela mesma recebeu), achado pela convencao"))
    (testing "a manifestacao anonima esta' la', sem identidade em coluna alguma"
      (let [[cab & ls] (ler-csv (texto (es "dados/participacao/manifestacao_ouvidoria.csv")))
            m (zipmap cab (first ls))]
        (is (= "true" (m "anonima")))
        (is (nil? (m "manifestante_identidade_id")))
        (is (nil? (m "created_by")))))
    (testing "a servidora segue identificada (funcao publica): id real na trilha e no identidade.csv; a cidada nao"
      (is (some #(and (= (str pessoa) (% "identidade_id")) (= "legislativo/protocolar" (% "acao"))) trilha))
      (is (= "10.0.0.1" ((first (filter #(= "legislativo/protocolar" (% "acao")) trilha)) "ip")))
      (let [p (texto (es "dados/identidade/identidade.csv"))]
        (is (str/includes? p (str pessoa)))
        (is (not (str/includes? p pseudo-cidada)))))
    (testing "a corrente foi conferida sobre os dados REAIS, e o LEIA-ME explica a troca"
      (is (= [true 5] ((juxt #(% "integra") #(% "total")) (json/read-value (texto (es "auditoria/verificacao.json"))))))
      (is (str/includes? (texto (es "LEIA-ME.txt")) "pseudônimo"))
      (is (str/includes? (texto (es "LEIA-ME.txt")) "art. 10, § 7º")))))

;; ---------------------------------------------------------------------------------------------
;; APAGAMENTO
;; ---------------------------------------------------------------------------------------------

(defn- tabelas-com-ente
  "Varredura GENERICA do catalogo: toda tabela (nao-particao) com `ente_id`, fora `admin_sistema` (supratenant, fica) e
  `ia` (o satelite)."
  []
  (mapv :t (sql! "SELECT format('%I.%I', n.nspname, c.relname) AS t
                    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                   WHERE c.relkind IN ('r', 'p') AND NOT c.relispartition
                     AND n.nspname NOT IN ('admin_sistema', 'ia', 'pg_catalog', 'information_schema')
                     AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'ente_id'
                                  AND a.attnum > 0 AND NOT a.attisdropped)
                   ORDER BY 1")))

(defn- contagens
  "{tabela linhas-do-ente} pelo dono (sem RLS: inclusive o staging), mais as filhas de teste pela FK."
  [ente]
  (-> (into (sorted-map) (for [t (tabelas-com-ente)]
                           [t (:n (first (sql! (str "SELECT count(*) AS n FROM " t " WHERE ente_id = ?") ente)))]))
      (assoc "teste_encerramento.anexo_remessa"
             (:n (first (sql! "SELECT count(*) AS n FROM teste_encerramento.anexo_remessa x
                                JOIN compliance.remessa_gerada r ON r.id = x.remessa_id WHERE r.ente_id = ?" ente)))
             "teste_encerramento.anexo_nota"
             (:n (first (sql! "SELECT count(*) AS n FROM teste_encerramento.anexo_nota y
                                JOIN teste_encerramento.anexo_remessa x ON x.id = y.anexo_id
                                JOIN compliance.remessa_gerada r ON r.id = x.remessa_id WHERE r.ente_id = ?" ente))))))

(defn- estado-das-protecoes
  "Os triggers de usuario e o FORCE RLS de todas as tabelas — tem de estar iguais antes e depois do apagamento."
  []
  {:triggers (sql! "SELECT tgrelid::regclass::text AS t, tgname, tgenabled::text FROM pg_trigger
                     WHERE NOT tgisinternal ORDER BY 1, 2")
   :force (sql! "SELECT c.oid::regclass::text AS t, c.relrowsecurity, c.relforcerowsecurity FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE c.relkind IN ('r', 'p') AND n.nspname NOT LIKE 'pg\\_%' ORDER BY 1")})

(defn- idp-fake [chamadas falhar?]
  (reify idp/IdentityProvider
    (apagar-realm! [_ ente]
      (swap! chamadas conj ente)
      (if @falhar? (throw (ex-info "keycloak fora do ar" {})) {:realm (str "ente-" ente) :existia? true}))))

(defn- ia-fake [chamadas falhar?]
  (reify plataforma-ia/PlataformaIA
    (apagar-ente [_ ente]
      (swap! chamadas conj ente)
      (if @falhar?
        (throw (ex-info "plataforma de IA indisponivel" {:tipo :ia/indisponivel}))
        {:ente_id (str ente) :apagados {:ia.trabalho 2 :ia.transcricao 1} :total 3}))))

(defn- deps [st & {:keys [falhar-idp falhar-ia] :or {falhar-idp (atom false) falhar-ia (atom false)}}]
  {:ds *pool* :objeto-store st
   :idp (idp-fake (atom []) falhar-idp) :plataforma-ia (ia-fake (atom []) falhar-ia)})

(defn- recusa [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (when (= :encerramento/recusado (:tipo (ex-data e))) (ex-message e)))))

(deftest a-funcao-recusa-sem-as-salvaguardas
  (let [{:keys [st a]} (duas-casas!)
        antes (contagens a)
        blobs-antes (set (keys @(:m st)))
        apagar #(encerramento/apagar-casa! (deps st) a %)]
    (testing "Casa ATIVA (sem encerramento em curso)"
      (let [{:keys [pedido]} (liberar-apagamento! a)]
        (sql! "UPDATE admin_sistema.ente SET estado = 'ativo', motivo_restricao = NULL WHERE ente_id = ?" a)
        (is (re-find #"encerramento em curso" (str (recusa #(apagar pedido)))))
        (sql! "UPDATE admin_sistema.ente SET estado = 'suspenso', motivo_restricao = 'inadimplencia' WHERE ente_id = ?" a)
        (is (re-find #"encerramento em curso" (str (recusa #(apagar pedido)))) "suspensa por inadimplencia nao basta")
        (sql! "UPDATE admin_sistema.ente SET motivo_restricao = 'encerramento_em_curso' WHERE ente_id = ?" a)
        (testing "pedido inexistente ou de outra Casa"
          (is (re-find #"nao ha pedido" (str (recusa #(apagar (random-uuid))))))
          (let [{outro :pedido} (liberar-apagamento! (:b (duas-casas!)))]
            (is (re-find #"nao ha pedido" (str (recusa #(apagar outro)))))))
        (testing "pedido nao aprovado"
          (sql! "UPDATE admin_sistema.pedido_restricao SET estado = 'aguardando', decidido_por = NULL, decidido_em = NULL
                 WHERE id = ?" pedido)
          (is (re-find #"nao foi aprovado" (str (recusa #(apagar pedido))))))
        (testing "o MESMO operador nas duas pontas (o CHECK da tabela ja' recusa; a funcao confere de novo)"
          ;; para chegar na funcao, a constraint sai SO' nesta transacao (que volta atras)
          (jdbc/with-transaction [tx *dono* {:rollback-only true}]
            (jdbc/execute! tx ["ALTER TABLE admin_sistema.pedido_restricao DROP CONSTRAINT pedido_restricao_duas_pessoas"])
            (jdbc/execute! tx ["UPDATE admin_sistema.pedido_restricao SET estado = 'aprovado', decidido_por = pedido_por,
                                decidido_em = now() WHERE id = ?" pedido])
            (is (thrown-with-msg? Exception #"aprovado por outro operador"
                                  (jdbc/execute! tx ["SELECT admin_sistema.apagar_dados_da_casa(?, ?)" a pedido])))))))
    (let [{:keys [pedido exportacao]} (liberar-apagamento! a)]
      (sql! "DELETE FROM admin_sistema.exportacao_casa WHERE ente_id = ? AND id <> ?" a exportacao)
      (testing "exportacao pronta mas SEM confirmacao de recebimento"
        (sql-cenario! "UPDATE admin_sistema.exportacao_casa SET confirmada_em = NULL, confirmada_por = NULL,
               confirmada_por_tipo = NULL WHERE id = ?" exportacao)
        (is (re-find #"nao confirmou o recebimento" (str (recusa #(apagar pedido))))))
      (testing "confirmada ha' 89 dias: a guarda ainda nao terminou"
        (sql-cenario! "UPDATE admin_sistema.exportacao_casa SET confirmada_em = now() - interval '89 days',
               confirmada_por = gen_random_uuid(), confirmada_por_tipo = 'admin_ente' WHERE id = ?" exportacao)
        (is (re-find #"guarda de 90 dias" (str (recusa #(apagar pedido))))))
      (testing "o role da aplicacao DENTRO do tenant nem executa a funcao (so' a Operacao)"
        (is (thrown-with-msg? Exception #"permission denied"
                              (tenancy/com-tenant* *pool* a
                                #(jdbc/execute! % ["SELECT admin_sistema.apagar_dados_da_casa(?, ?)" a pedido]))))))
    (is (= antes (contagens a)) "nenhuma recusa apagou linha alguma")
    (is (= blobs-antes (set (keys @(:m st)))) "nem blob algum (a conferencia vem antes de tudo)")))

(deftest apagamento-completo-zera-a-e-nao-toca-b
  (let [{:keys [st a b compartilhada pa pb]} (duas-casas!)
        {:keys [pedido exportacao sha256]} (liberar-apagamento! a)
        _ (store/guardar! st (str "exportacoes/" a "/" exportacao ".zip") (.getBytes "zip") "application/zip")
        _ (store/guardar! st (str "exportacoes/" b "/outra.zip") (.getBytes "zip") "application/zip")
        b-antes (contagens b)
        a-antes (contagens a)
        blobs-b (set (filter #(str/includes? % (str b)) (keys @(:m st))))
        protecoes (estado-das-protecoes)
        admin-antes (sql! "SELECT * FROM admin_sistema.ente WHERE ente_id = ?" a)
        federais (sql! "SELECT id FROM normas.norma WHERE ente_id IS NULL")
        r (encerramento/apagar-casa! (deps st) a pedido)]
    (testing "a Casa tinha dado nas tabelas append-only/imutaveis e no staging"
      (is (= 5 (a-antes "auditoria.registro")))
      (is (= 1 (a-antes "auditoria.selo_diario")))
      (is (= 1 (a-antes "legislativo.protocolo_geral")))
      (is (= 2 (a-antes "sessoes.gravacao_segmento")) "inclusive o staging")
      (is (= 2 (a-antes "participacao.anexo")) "o anexo da resposta (Casa) e o do requerente (append-only)"))
    (testing "ZERO linhas de A em toda tabela com ente_id (varredura do catalogo) — so' ficam os eventos da Operacao"
      (let [depois (contagens a)]
        (is (= {"shared.outbox" 1} (into {} (filter (comp pos? val)) depois)) (pr-str (filter (comp pos? val) depois)))
        (is (= ["admin_sistema.casa.suspensa"] (mapv :tipo (sql! "SELECT tipo FROM shared.outbox WHERE ente_id = ?" a))))))
    (testing "B intacta, linha por linha da contagem"
      (is (= b-antes (contagens b))))
    (testing "a pessoa so' de A sai (com o vinculo gov.br); a compartilhada e a so' de B ficam"
      (is (empty? (sql! "SELECT 1 FROM identidade.identidade WHERE id = ?" (:pessoa pa))))
      (is (empty? (sql! "SELECT 1 FROM identidade.identidade_externa WHERE identidade_id = ?" (:pessoa pa))))
      (is (seq (sql! "SELECT 1 FROM identidade.identidade WHERE id = ?" compartilhada)))
      (is (seq (sql! "SELECT 1 FROM identidade.identidade WHERE id = ?" (:pessoa pb))))
      (is (seq (sql! "SELECT 1 FROM identidade.identidade_externa WHERE identidade_id = ?" (:pessoa pb)))))
    (testing "referencia e supratenant ficam: normas federais, o registro da Casa, o pedido, a exportacao"
      (is (= federais (sql! "SELECT id FROM normas.norma WHERE ente_id IS NULL")))
      (is (= admin-antes (sql! "SELECT * FROM admin_sistema.ente WHERE ente_id = ?" a)))
      (is (seq (sql! "SELECT 1 FROM admin_sistema.exportacao_casa WHERE id = ? AND sha256 = ?" exportacao sha256)))
      (is (seq (sql! "SELECT 1 FROM admin_sistema.pedido_restricao WHERE id = ?" pedido))))
    (testing "o object storage: nada de A (nem as exportacoes); B inteira"
      (is (empty? (filter #(str/includes? % (str a)) (keys @(:m st)))))
      (is (= blobs-b (set (filter #(str/includes? % (str b)) (keys @(:m st)))))))
    (testing "os triggers e o FORCE RLS seguem exatamente como estavam"
      (is (= protecoes (estado-das-protecoes))))
    (testing "o resumo"
      (is (= 5 (get-in r [:tabelas "auditoria.registro"])))
      (is (= 2 (get-in r [:tabelas "sessoes.gravacao_segmento"])))
      (is (= 1 (get-in r [:tabelas "teste_encerramento.anexo_nota"])))
      (is (= 2 (get-in r [:tabelas "identidade.identidade"])) "a servidora so' de A e a cidada")
      (is (= 1 (get-in r [:tabelas "identidade.identidade_externa"])))
      (is (contains? (:tabelas r) "paineis.pendencia") "a lista inteira do inventario, zeros inclusive")
      (is (= (reduce + (vals (:tabelas r))) (:linhas-total r)))
      (is (= 5 (:objetos r)) "remessa + gravacao + folha + os dois anexos do atendimento (Casa e requerente)")
      (is (= 1 (:exportacoes-apagadas r)))
      (is (= [] (:objetos-fora-da-convencao r)))
      (is (true? (:realm-apagado? r)))
      (is (= {:pendente false :apagados {"ia.trabalho" 2 "ia.transcricao" 1} :total 3} (:ia r)))
      (is (= {:id exportacao :sha256 sha256} (select-keys (:exportacao r) [:id :sha256])))
      (is (= [] (:pendencias r)))
      (is (true? (:completo? r))))
    (testing "o modo de replicacao volta ao normal na mesma transacao (o chamador nao herda triggers desligados)"
      (jdbc/with-transaction [tx *pool* {:rollback-only true}]
        (jdbc/execute! tx ["SELECT admin_sistema.apagar_dados_da_casa(?, ?)" a pedido])
        (is (= "origin" (:s (jdbc/execute-one! tx ["SELECT current_setting('session_replication_role') AS s"] opts))))))))

(deftest a-funcao-espera-o-evento-que-o-relay-ja-pegou
  ;; mig 0177: o relay pegou um evento da Casa (linha travada FOR UPDATE, como em `kernel.outbox/drenar-um!`) e o
  ;; consumidor ainda vai gravar. A funcao espera essa tx terminar ANTES do primeiro DELETE — sem isso, a linha gravada
  ;; pelo consumidor sobrevivia ao apagamento (os DELETEs ja' tinham passado pela tabela dela).
  (let [{:keys [a]} (duas-casas!)
        {:keys [pedido]} (liberar-apagamento! a)
        ev (:id (first (sql! "SELECT id FROM shared.outbox WHERE ente_id = ? AND processed_at IS NULL
                              AND tipo NOT LIKE 'admin\\_sistema.%'" a)))
        pegou (promise) soltar (promise)
        relay (future
                (jdbc/with-transaction [tx *dono*]
                  (jdbc/execute! tx ["SELECT 1 FROM shared.outbox WHERE id = ? FOR UPDATE" ev])
                  (deliver pegou true)
                  @soltar
                  ;; uma tabela FOLHA (nivel 0) que vem antes de `shared` na ordem do apagamento: sem a espera,
                  ;; o DELETE dela passava antes de o consumidor commitar
                  (jdbc/execute! tx ["INSERT INTO auditoria.selo_diario (ente_id, dia, seq, selo)
                                      VALUES (?, '2026-10-01', 2, 'gravado pelo consumidor')" a])
                  (jdbc/execute! tx ["UPDATE shared.outbox SET processed_at = now() WHERE id = ?" ev])))
        _ (deref pegou 5000 nil)
        apagando (future (jdbc/with-transaction [tx *pool*]
                           (jdbc/execute! tx ["SELECT admin_sistema.apagar_dados_da_casa(?, ?)" a pedido])))]
    (is (some? ev))
    (Thread/sleep 500)
    (is (not (realized? apagando)) "a funcao esperou o relay")
    (deliver soltar true)
    (deref relay 10000 nil)
    (deref apagando 10000 nil)
    (is (empty? (sql! "SELECT 1 FROM auditoria.selo_diario WHERE ente_id = ?" a)) "a linha do consumidor saiu junto")
    (is (empty? (sql! "SELECT 1 FROM shared.outbox WHERE ente_id = ? AND tipo NOT LIKE 'admin\\_sistema.%'" a)))))

(deftest retomada-depois-de-falha-no-realm-e-na-ia
  (let [{:keys [st a b]} (duas-casas!)
        {:keys [pedido]} (liberar-apagamento! a)
        b-antes (contagens b)
        falhar-idp (atom true) falhar-ia (atom true)
        d (deps st :falhar-idp falhar-idp :falhar-ia falhar-ia)
        r1 (encerramento/apagar-casa! d a pedido)]
    (testing "1a chamada: o banco e os blobs ja' foram; realm e IA pendentes (nunca sucesso fingido)"
      (is (pos? (:linhas-total r1)))
      (is (false? (:realm-apagado? r1)))
      (is (= {:pendente true :motivo "keycloak fora do ar"} (:realm r1)))
      (is (true? (get-in r1 [:ia :pendente])))
      (is (= [:realm :ia] (:pendencias r1)))
      (is (false? (:completo? r1))))
    (reset! falhar-idp false)
    (reset! falhar-ia false)
    ;; o fluxo pode ter marcado a Casa como encerrada entre as duas: a retomada continua valendo
    (sql! "UPDATE admin_sistema.ente SET estado = 'encerrado', motivo_restricao = NULL, encerrada_em = now() WHERE ente_id = ?" a)
    (let [r2 (encerramento/apagar-casa! d a pedido)]
      (testing "2a chamada termina sem erro: banco ja' vazio (zeros), realm e IA feitos"
        (is (zero? (:linhas-total r2)))
        (is (zero? (:objetos r2)))
        (is (true? (:realm-apagado? r2)))
        (is (false? (get-in r2 [:ia :pendente])))
        (is (true? (:completo? r2)))))
    (is (every? zero? (vals (dissoc (contagens a) "shared.outbox"))))
    (is (= b-antes (contagens b)))))

(deftest sem-idp-ou-ia-configurados-fica-pendente
  (let [{:keys [st a]} (duas-casas!)
        {:keys [pedido]} (liberar-apagamento! a)
        r (encerramento/apagar-casa! {:ds *pool* :objeto-store st} a pedido)]
    (is (= [:realm :ia] (:pendencias r)))
    (is (re-find #"nao configurado" (get-in r [:ia :motivo])))))

;; ---------------------------------------------------------------------------------------------
;; DONO NAO-SUPERUSER (Postgres gerenciado): o caminho LOCK + NO FORCE + DISABLE TRIGGER, de verdade
;; ---------------------------------------------------------------------------------------------

(defn- url-irma
  "A URL do banco de teste com `sufixo` no nome (o banco irmao, descartavel)."
  [url sufixo]
  (str/replace url #"/([^/?]+)(\?.*)?$" (str "/$1" sufixo "$2")))

(defn- preparar-banco-de-dono-restrito!
  "Um banco IRMAO cujo dono (quem roda as migrations, logo o dono das tabelas e da funcao SECURITY DEFINER) NAO e'
  superuser — como num Postgres gerenciado. Recriado a cada execucao. Devolve a URL, ou nil se o usuario do teste nao
  pode criar role/banco (o teste entao avisa e nao roda)."
  [url]
  (let [nome (second (re-find #"/([^/?]+)(\?.*)?$" (url-irma url "_dono_restrito")))]
    (when (:rolsuper (first (sql! "SELECT rolsuper FROM pg_roles WHERE rolname = current_user")))
      (doseq [s ["DO $$ BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'oplenario_dono_restrito') THEN
                      CREATE ROLE oplenario_dono_restrito LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE PASSWORD 'dono_restrito';
                    END IF;
                  END $$"
                 ;; as migrations concedem os roles de runtime (GRANT oplenario_operacao TO oplenario_pool): no PG16 isso
                 ;; pede ADMIN OPTION — sem herdar nem assumir nenhum deles
                 (str "GRANT oplenario_app, oplenario_relay, oplenario_id_resolver, oplenario_operacao, oplenario_pool"
                      " TO oplenario_dono_restrito WITH ADMIN OPTION, INHERIT FALSE, SET FALSE")
                 (str "DROP DATABASE IF EXISTS " nome " WITH (FORCE)")
                 (str "CREATE DATABASE " nome " OWNER oplenario_dono_restrito")]]
        (jdbc/execute! *dono* [s]))
      (url-irma url "_dono_restrito"))))

(deftest dono-nao-superuser-apaga-com-lock-e-restaura-as-protecoes
  (let [cfg (config/carregar)]
    (if-let [url (preparar-banco-de-dono-restrito! (get-in cfg [:db :jdbc-url]))]
      (let [cfg-r (assoc-in cfg [:db :jdbc-url] url)
            dono-r (component/start (datasource/datasource (update cfg-r :db assoc :user "oplenario_dono_restrito"
                                                                   :password "dono_restrito")))
            super-r (component/start (datasource/datasource cfg-r))
            pool-r (component/start (datasource/datasource (update cfg-r :db assoc :user "oplenario_pool"
                                                                   :password "oplenario_dev_pool")))]
        (try
          (migracao/migrar! (:ds dono-r))
          (criar-filhas-de-teste! (:ds dono-r))
          (binding [*dono* (:ds super-r) *pool* (:ds pool-r)]
            (is (false? (:rolsuper (first (sql! "SELECT r.rolsuper FROM pg_proc p JOIN pg_roles r ON r.oid = p.proowner
                                                 WHERE p.proname = 'apagar_dados_da_casa'"))))
                "a funcao do apagamento e' do dono NAO-superuser (o caminho com lock)")
            (let [{:keys [st a b compartilhada pa]} (duas-casas!)
                  {:keys [pedido]} (liberar-apagamento! a)
                  b-antes (contagens b)
                  protecoes (estado-das-protecoes)
                  r (encerramento/apagar-casa! (deps st) a pedido)]
              (is (= 5 (get-in r [:tabelas "auditoria.registro"])) "append-only apagado (trigger contornado)")
              (is (= 2 (get-in r [:tabelas "sessoes.gravacao_segmento"])) "o staging tambem (a RLS forcada contornada)")
              (is (= {"shared.outbox" 1} (into {} (filter (comp pos? val)) (contagens a))))
              (is (= b-antes (contagens b)))
              (is (empty? (sql! "SELECT 1 FROM identidade.identidade WHERE id = ?" (:pessoa pa))))
              (is (seq (sql! "SELECT 1 FROM identidade.identidade WHERE id = ?" compartilhada))
                  "a pessoa de B segue: a checagem de orfa enxergou o vinculo de B (sem a RLS do dono)")
              (is (= protecoes (estado-das-protecoes)) "triggers ligados e FORCE RLS de volta, como antes")
              (is (thrown-with-msg? Exception #"imutabilidade"
                                    (sql! "DELETE FROM auditoria.registro WHERE ente_id = ?" b))
                  "e a trilha de B continua append-only")
              (testing "retomada no caminho com lock: zeros, sem erro"
                (is (zero? (:linhas-total (encerramento/apagar-casa! (deps st) a pedido)))))))
          (finally
            (component/stop pool-r)
            (component/stop super-r)
            (component/stop dono-r)
            (jdbc/execute! *dono* [(str "DROP DATABASE IF EXISTS "
                                        (second (re-find #"/([^/?]+)(\?.*)?$" url)) " WITH (FORCE)")]))))
      (println "AVISO: o usuario do teste nao e' superuser — o caminho do dono restrito nao foi exercitado"))))
