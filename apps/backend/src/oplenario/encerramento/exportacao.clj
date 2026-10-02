(ns oplenario.encerramento.exportacao
  "HOST (§22.10) — a EXPORTACAO COMPLETA da Casa (ADR-0018, Eixo 4.2; feature 9.6): um ZIP em formato aberto com
  tudo o que e' da Casa, para ela levar a outro sistema e para provar o que entregamos.

  Estrutura do ZIP:
    LEIA-ME.txt                      — o que e' cada parte, em portugues
    dicionario.csv                   — esquema, tabela, coluna, tipo, nulo, comentario (de tudo que esta em dados/)
    dados/<esquema>/<tabela>.csv     — TODAS as linhas da Casa de cada tabela exportada (inventario descoberto)
    dados/identidade/identidade.csv  — as pessoas que as linhas da Casa referenciam (nome e CPF)
    arquivos/<chave>                 — os blobs da Casa no object storage (atas, folhas, publicacoes, gravacoes,
                                       remessas), na chave original
    auditoria/verificacao.json       — a corrente da trilha (ADR-0017) conferida na hora da exportacao
    auditoria/selos-do-dia.csv       — os selos do dia publicados (e as ancoras na Operacao, quando houver)
    manifesto.json                   — ente, quando, versao do formato, linhas por tabela e, por arquivo, caminho,
                                       bytes e sha256 (o manifesto nao lista a si mesmo)

  ISOLAMENTO: toda leitura de dado de tenant roda em `tenancy/com-tenant*` (role `oplenario_app`, NOBYPASSRLS, com o
  GUC da Casa) — e so' se exportam tabelas isoladas por RLS (ou filhas delas). Vazar outra Casa exigiria a RLS falhar e
  o predicado do inventario tambem. As linhas em STAGING de importacao (lote nao efetivado) nao sao da Casa ainda e nao
  entram.

  O ZIP e' escrito em arquivo temporario (streaming: linha a linha, blob a blob) e sobe por `guardar-stream!`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [oplenario.encerramento.arquivos :as arquivos]
            [oplenario.encerramento.csv :as csv]
            [oplenario.encerramento.inventario :as inventario]
            [oplenario.kernel.components.objeto-store :as store]
            [oplenario.kernel.tenancy :as tenancy])
  (:import (java.io BufferedWriter File FileInputStream FileOutputStream InputStream OutputStream OutputStreamWriter)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.sql Connection ResultSet ResultSetMetaData)
           (java.time Instant)
           (java.util HexFormat UUID)
           (java.util.zip ZipEntry ZipOutputStream)))

(set! *warn-on-reflection* true)

(def formato "oplenario.exportacao-da-casa")
(def versao-do-formato 1)

(defn chave-do-zip [ente-id exportacao-id] (str arquivos/pasta-das-exportacoes ente-id "/" exportacao-id ".zip"))

(def ^:private mapa-json (json/object-mapper {:pretty true}))

(defn- hex [^bytes b] (.formatHex (HexFormat/of) b))

;; ---------------------------------------------------------------------------------------------
;; uma entrada do ZIP, medida (bytes + sha256 do conteudo descomprimido)
;; ---------------------------------------------------------------------------------------------

(defn- saida-medida
  "OutputStream que escreve em `destino` e mede o que passou (sha256 + bytes). `close` so' descarrega: quem fecha o
  ZIP e' o dono dele."
  ^OutputStream [^OutputStream destino ^MessageDigest md ^longs n]
  (proxy [OutputStream] []
    (write
      ([x]
       (if (bytes? x)
         (let [^bytes b x]
           (.write destino b) (.update md b) (aset n 0 (+ (aget n 0) (alength b))))
         (let [i (int x)]
           (.write destino i) (.update md (unchecked-byte i)) (aset n 0 (inc (aget n 0))))))
      ([x off len]
       (let [^bytes b x off (int off) len (int len)]
         (.write destino b off len) (.update md b off len) (aset n 0 (+ (aget n 0) len)))))
    (flush [] (.flush destino))
    (close [] (.flush destino))))

(defn- entrada!
  "Abre a entrada `caminho` no ZIP, entrega a `escrever` (fn [^OutputStream]) e devolve {:caminho :bytes :sha256}
  junto com o que `escrever` devolveu, em `:valor`."
  [^ZipOutputStream zos caminho escrever]
  (let [md (MessageDigest/getInstance "SHA-256")
        n (long-array 1)
        _ (.putNextEntry zos (ZipEntry. ^String caminho))
        valor (escrever (saida-medida zos md n))]
    (.closeEntry zos)
    {:caminho caminho :bytes (aget n 0) :sha256 (hex (.digest md)) :valor valor}))

(defn- escritor ^BufferedWriter [^OutputStream o]
  (BufferedWriter. (OutputStreamWriter. o StandardCharsets/UTF_8) 65536))

(defn- entrada-texto! [zos caminho ^String texto]
  (entrada! zos caminho (fn [^OutputStream o] (.write o (.getBytes texto StandardCharsets/UTF_8)))))

;; ---------------------------------------------------------------------------------------------
;; dados/<esquema>/<tabela>.csv
;; ---------------------------------------------------------------------------------------------

(defn- escrever-tabela!
  "Streaming: SELECT das linhas da Casa (predicado do inventario, ordenado pela PK) -> CSV. Devolve quantas linhas."
  [^Connection tx t ^UUID ente-id ^OutputStream o]
  (let [[pred n] (inventario/com-parametro (:predicado t))
        pk (inventario/chave-primaria tx t)
        sql (str "SELECT t.* FROM " (inventario/tabela-sql t) " AS t WHERE " pred
                 (when (seq pk) (str " ORDER BY " (str/join ", " (map #(str "t." (inventario/ident %)) pk)))))
        w (escritor o)]
    (with-open [ps (.prepareStatement tx sql)]
      (.setFetchSize ps 500)
      (dotimes [i n] (.setObject ps (int (inc i)) ente-id))
      (with-open [^ResultSet r (.executeQuery ps)]
        (let [^ResultSetMetaData md (.getMetaData r)
              nc (.getColumnCount md)
              cols (range 1 (inc nc))
              tipos (mapv #(.getColumnTypeName md (int %)) cols)]
          (.write w (csv/linha (map #(.getColumnName md (int %)) cols)))
          (let [linhas (loop [k 0]
                         (if (.next r)
                           (do (.write w (csv/linha (map (fn [i] (csv/->texto (.getObject r (int i)) (tipos (dec i))))
                                                         cols)))
                               (recur (inc k)))
                           k))]
            (.flush w)
            linhas))))))

(defn- pessoas-referenciadas
  "Na tx do tenant: os ids de `identidade.identidade` que as linhas da Casa referenciam por FK."
  [^Connection tx tabelas ente-id]
  (into #{}
        (for [t tabelas
              col (inventario/colunas-que-apontam-para tx t "identidade.identidade")
              :let [[pred n] (inventario/com-parametro (:predicado t))
                    sql (str "SELECT DISTINCT t." (inventario/ident col) " FROM " (inventario/tabela-sql t)
                             " AS t WHERE (" pred ") AND t." (inventario/ident col) " IS NOT NULL")]
              v (with-open [ps (.prepareStatement tx sql)]
                  (dotimes [i n] (.setObject ps (int (inc i)) ente-id))
                  (with-open [rs (.executeQuery ps)]
                    (loop [acc []] (if (.next rs) (recur (conj acc (.getObject rs 1))) acc))))]
          v)))

(def ^:private tabela-pessoas {:esquema "identidade" :tabela "identidade"})

(defn- escrever-pessoas!
  "As pessoas da Casa: so' os ids que as linhas DELA referenciam (lidos sob RLS), entao nunca alguem so' de outra
  Casa. Lidas com o role do pool (o resolvedor de identidade le esta tabela supratenant)."
  [ds ids ^OutputStream o]
  (let [w (escritor o)
        linhas (when (seq ids)
                 (jdbc/execute! ds ["SELECT id, cpf, nome, criado_em FROM identidade.identidade
                                     WHERE id = ANY (?) ORDER BY id" (into-array UUID ids)]
                                {:builder-fn rs/as-unqualified-maps}))]
    (.write w (csv/linha ["id" "cpf" "nome" "criado_em"]))
    (doseq [{:keys [id cpf nome criado_em]} linhas]
      (.write w (csv/linha (map csv/->texto [id cpf nome criado_em]))))
    (.flush w)
    (count linhas)))

;; ---------------------------------------------------------------------------------------------
;; LEIA-ME, auditoria, manifesto
;; ---------------------------------------------------------------------------------------------

(defn leia-me [ente-id ^Instant gerado-em]
  (str "EXPORTAÇÃO COMPLETA DA CÂMARA — O Plenário\r\n"
       "Casa (ente): " ente-id "\r\n"
       "Gerada em: " gerado-em " (UTC)\r\n"
       "Formato: " formato ", versão " versao-do-formato "\r\n\r\n"
       "O QUE HÁ AQUI\r\n"
       "- dados/<esquema>/<tabela>.csv: todas as linhas da Câmara em cada tabela, uma por linha. A primeira linha é o\r\n"
       "  cabeçalho com os nomes das colunas.\r\n"
       "- dados/identidade/identidade.csv: as pessoas (nome e CPF) que aparecem nos dados da Câmara.\r\n"
       "- dicionario.csv: para cada coluna exportada, o tipo, se aceita vazio e a descrição.\r\n"
       "- arquivos/: os documentos guardados (atas, folhas de presença, publicações, gravações, remessas ao TCE),\r\n"
       "  com o mesmo caminho que tinham no sistema — as colunas *_ref e *_uri dos dados apontam para eles.\r\n"
       "- auditoria/: a trilha de auditoria da Câmara está em dados/auditoria/registro.csv; aqui estão a conferência\r\n"
       "  da corrente feita na hora da exportação e os selos do dia publicados.\r\n"
       "- manifesto.json: a lista de todos os arquivos com tamanho e SHA-256, e o total de linhas por tabela.\r\n\r\n"
       "COMO LER OS CSV\r\n"
       "- Texto em UTF-8, separador vírgula, linhas terminadas em CRLF (RFC 4180).\r\n"
       "- Campo vazio sem aspas = sem valor (NULL); \"\" = texto vazio.\r\n"
       "- Datas e horas em ISO-8601, em UTC (ex.: 2026-10-02T14:33:20Z); datas sem hora como AAAA-MM-DD.\r\n"
       "- Campos JSON e listas aparecem como JSON; conteúdo binário aparece em base64.\r\n\r\n"
       "COMO CONFERIR A INTEGRIDADE\r\n"
       "- Calcule o SHA-256 de cada arquivo e compare com o manifesto.json.\r\n"
       "- O SHA-256 do arquivo ZIP inteiro é o que ficou registrado no O Plenário como prova da entrega.\r\n"
       "- auditoria/verificacao.json explica como recalcular a corrente da trilha de auditoria.\r\n"))

(def ^:private como-conferir-a-corrente
  (str "Cada registro de dados/auditoria/registro.csv sela o anterior da mesma Casa: selo = sha256 hexadecimal de "
       "selo_anterior|ente_id|seq|id|ocorrido_em|ator_tipo|identidade_id|papeis(separados por ,)|via_agente|acao|classe|"
       "recurso_tipo|recurso_id|rotulo|campos(separados por ,)|decisao|status_http|canal|detalhe(JSON com chaves "
       "ordenadas), com vazio no lugar de nulo e selo_anterior vazio no primeiro registro. O IP fica fora do selo "
       "(anulado depois de 6 meses). A corrente esta integra se seq vai de 1 a n sem buraco e cada selo confere. Os selos "
       "do dia (auditoria/selos-do-dia.csv) sao a cabeca da corrente ao fim de cada dia; o O Plenario os ancorou na "
       "corrente da Operacao (ADR-0017)."))

(defn- escrever-csv! [^OutputStream o cabecalho linhas]
  (let [w (escritor o)]
    (.write w (csv/linha cabecalho))
    (doseq [l linhas] (.write w (csv/linha (map csv/->texto l))))
    (.flush w)
    (count linhas)))

;; ---------------------------------------------------------------------------------------------
;; a exportacao
;; ---------------------------------------------------------------------------------------------

(defn- sha256-do-arquivo [^File f]
  (let [md (MessageDigest/getInstance "SHA-256")
        buf (byte-array 65536)]
    (with-open [in (FileInputStream. f)]
      (loop [] (let [k (.read in buf)] (when (pos? k) (.update md buf 0 k) (recur)))))
    (hex (.digest md))))

(defn- caminho-do-blob
  "`arquivos/<chave>`; chave com `..`, `/` inicial ou `\\` nao vira caminho (zip-slip): vai pelo sha256 da chave."
  [^String chave]
  (if (or (str/starts-with? chave "/") (str/includes? chave "\\")
          (some #{".." "."} (str/split chave #"/")))
    (str "arquivos/_chave-nao-portavel/" (hex (.digest (MessageDigest/getInstance "SHA-256")
                                                         (.getBytes chave StandardCharsets/UTF_8))))
    (str "arquivos/" chave)))

(defn- escrever-dados!
  "dados/ + dicionario.csv. UMA transacao de tenant, REPEATABLE READ e so' leitura (um snapshot unico sob RLS).
  Devolve {:contagens :refs} (as chaves que as linhas apontam)."
  [zos regs! ds tabelas ente-id]
  (let [contagens (atom (sorted-map))
        dicionario (atom [])
        {:keys [refs pessoas]}
        (tenancy/com-tenant* ds ente-id {:isolation :repeatable-read :read-only true}
          (fn [tx]
            (doseq [t tabelas]
              (let [n (regs! (entrada! zos (str "dados/" (:esquema t) "/" (:tabela t) ".csv")
                                       #(escrever-tabela! tx t ente-id %)))]
                (swap! contagens assoc (inventario/nome t) n)
                (swap! dicionario into (map #(assoc % :esquema (:esquema t) :tabela (:tabela t))
                                            (inventario/colunas tx t)))))
            {:refs (arquivos/referencias tx tabelas ente-id)
             :pessoas (pessoas-referenciadas tx tabelas ente-id)}))
        n (regs! (entrada! zos "dados/identidade/identidade.csv" #(escrever-pessoas! ds pessoas %)))]
    (swap! contagens assoc (inventario/nome tabela-pessoas) n)
    (swap! dicionario into (map #(assoc % :esquema "identidade" :tabela "identidade")
                                (filter (comp #{"id" "cpf" "nome" "criado_em"} :coluna)
                                        (inventario/colunas ds tabela-pessoas))))
    (regs! (entrada! zos "dicionario.csv"
                     #(escrever-csv! % ["esquema" "tabela" "coluna" "tipo" "nulo" "comentario"]
                                     (map (juxt :esquema :tabela :coluna :tipo :nulo? :comentario) @dicionario))))
    {:contagens @contagens :refs refs}))

(defn- escrever-arquivos!
  "arquivos/: a convencao `<pasta>/<ente>/` (pastas descobertas) + as referencias das linhas. -> as ausentes."
  [zos regs! objeto-store ente-id refs]
  (vec (for [k (into (arquivos/por-convencao objeto-store ente-id #{arquivos/pasta-das-exportacoes}) refs)
             :let [presente? (when-let [^InputStream in (store/abrir objeto-store k)]
                               (with-open [in in]
                                 (regs! (entrada! zos (caminho-do-blob k) #(do (io/copy in ^OutputStream %) nil)))
                                 true))]
             :when (not presente?)]
         k)))

(defn- escrever-auditoria!
  "auditoria/: a corrente conferida AGORA pelo modulo de auditoria (seam) + os selos do dia (+ as ancoras). -> a
  verificacao."
  [zos regs! auditoria ente-id gerado-em]
  (let [v ((:verificar auditoria) ente-id)]
    (regs! (entrada-texto! zos "auditoria/verificacao.json"
                           (json/write-value-as-string
                            (array-map "ente_id" (str ente-id) "verificada_em" (str gerado-em)
                                       "integra" (boolean (:integra v)) "total" (:total v) "cabeca" (:cabeca v)
                                       "quebra_em" (:quebra-em v) "como_conferir" como-conferir-a-corrente)
                            mapa-json)))
    (regs! (entrada! zos "auditoria/selos-do-dia.csv"
                     #(escrever-csv! % ["dia" "seq" "selo"]
                                     (map (juxt :dia :seq :selo)
                                          (sort-by (comp str :dia)
                                                   (when-let [f (:selos-do-dia auditoria)] (f ente-id)))))))
    (when-let [ancoras (:ancoras auditoria)]
      (regs! (entrada! zos "auditoria/ancoras-da-operacao.csv"
                       #(escrever-csv! % ["em" "dia" "seq" "selo_do_dia" "selo_da_operacao"]
                                       (map (juxt :em :dia :seq :selo-do-dia :selo-da-operacao) (ancoras ente-id))))))
    v))

(defn- manifesto [ente-id exportacao-id gerado-em entradas contagens ausentes verificacao]
  ;; array-map: o JSON sai na ordem de leitura (identificacao, totais, tabelas, arquivos)
  (let [blobs (filter #(str/starts-with? (:caminho %) "arquivos/") entradas)]
    (array-map
     "formato" formato
     "versao" versao-do-formato
     "ente_id" (str ente-id)
     "exportacao_id" (str exportacao-id)
     "gerado_em" (str gerado-em)
     "totais" (array-map "tabelas" (count contagens)
                         "linhas" (reduce + 0 (vals contagens))
                         "arquivos_da_casa" (count blobs)
                         "bytes_dos_arquivos_da_casa" (reduce + 0 (map :bytes blobs))
                         "entradas" (inc (count entradas)))
     "auditoria" (array-map "integra" (boolean (:integra verificacao))
                            "total" (:total verificacao)
                            "cabeca" (:cabeca verificacao))
     "tabelas" (into (sorted-map) contagens)
     "referencias_ausentes" ausentes
     "arquivos" (mapv (fn [{:keys [caminho bytes sha256]}] (array-map "caminho" caminho "bytes" bytes "sha256" sha256))
                      entradas))))

(defn exportar!
  "Ver `oplenario.encerramento/exportar-casa!` (o contrato e os `deps`)."
  [{:keys [ds objeto-store auditoria agora]} ente-id exportacao-id]
  (let [ente-id (UUID/fromString (str ente-id))
        gerado-em (if agora (agora) (Instant/now))
        chave (chave-do-zip ente-id exportacao-id)
        tmp (File/createTempFile "exportacao-casa-" ".zip")]
    (try
      (when-not (and ds objeto-store (:verificar auditoria))
        (throw (ex-info "faltam dependencias (:ds, :objeto-store, :auditoria {:verificar ...})" {})))
      (let [tabelas (vec (sort-by inventario/nome (filter :exporta? (inventario/inventario ds))))
            entradas (atom [])
            regs! (fn [e] (swap! entradas conj (dissoc e :valor)) (:valor e))
            m (with-open [zos (ZipOutputStream. (io/output-stream tmp))]
                (regs! (entrada-texto! zos "LEIA-ME.txt" (leia-me ente-id gerado-em)))
                (let [{:keys [contagens refs]} (escrever-dados! zos regs! ds tabelas ente-id)
                      ausentes (escrever-arquivos! zos regs! objeto-store ente-id refs)
                      v (escrever-auditoria! zos regs! auditoria ente-id gerado-em)
                      m (manifesto ente-id exportacao-id gerado-em @entradas contagens ausentes v)]
                  ;; o manifesto por ultimo: descreve todas as entradas anteriores (nao a si mesmo)
                  (entrada-texto! zos "manifesto.json" (json/write-value-as-string m mapa-json))
                  m))
            ;; o ZIP ja' fechado (diretorio central escrito e descarregado): mede e sobe o arquivo inteiro
            sha (sha256-do-arquivo tmp)
            tam (.length tmp)]
        (with-open [in (FileInputStream. tmp)]
          (store/guardar-stream! objeto-store chave in "application/zip"))
        {:chave chave :sha256 sha :bytes tam :manifesto m})
      (catch Exception e
        (throw (ex-info (str "A exportação da Casa falhou: " (or (ex-message e) (.getName (class e))))
                        {:tipo :encerramento/exportacao-falhou :ente-id ente-id :exportacao-id exportacao-id} e)))
      (finally
        (.delete tmp)))))
