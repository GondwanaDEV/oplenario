(ns oplenario.encerramento.inventario
  "HOST (§22.10) — o INVENTARIO DESCOBERTO das tabelas de uma Casa (ADR-0018, Eixo 4). A regra mora no banco
  (`admin_sistema.inventario_da_casa()`, migration `…171`) para a exportacao e o apagamento lerem a MESMA lista:

  - entram as tabelas com `ente_id` fora de `admin_sistema` (supratenant), `ia` (o satelite e' dono do proprio schema)
    e `public`/sistema; e as FILHAS sem `ente_id` que apontam por FK para uma delas (recursivo);
  - nunca entram as tabelas de REFERENCIA (municipios, tribunais, catalogo do motor) — nao tem `ente_id` nem FK para
    tenant; e as linhas de referencia de `ente_id` nulo (`normas` federais) ficam, pelo proprio predicado;
  - `exporta?` = a tabela e' isolada por RLS (ou e' filha de uma que e'). As tabelas de tenant sem RLS sao
    infraestrutura ou segredo (sessoes e credenciais por hash, outbox, inbox, feed core<->IA): apagadas, nunca
    exportadas.

  Cada entrada traz o `predicado` que acha as linhas da Casa, com `$1` no lugar do ente (alias da tabela = `t`)."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(set! *warn-on-reflection* true)

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(defn inventario
  "[{:esquema :tabela :nivel :exporta? :predicado}] — na ordem de apagar (filhas antes dos pais). `conectavel` = uma
  conexao/datasource com o role da Operacao (o pool herda `oplenario_operacao`)."
  [conectavel]
  (mapv (fn [{:keys [exporta] :as r}] (-> r (dissoc :exporta) (assoc :exporta? (boolean exporta))))
        (jdbc/execute! conectavel ["SELECT esquema, tabela, nivel, exporta, predicado
                                      FROM admin_sistema.inventario_da_casa()"] opts)))

(defn nome [{:keys [esquema tabela]}] (str esquema "." tabela))

(defn ident
  "Identificador SQL entre aspas (o catalogo so' devolve nomes validos, mas o texto vira SQL: escapa mesmo assim)."
  [s]
  (str \" (str/replace s "\"" "\"\"") \"))

(defn tabela-sql [{:keys [esquema tabela]}] (str (ident esquema) "." (ident tabela)))

(defn com-parametro
  "O predicado do inventario (`$1` = o ente) -> [sql-com-? n-de-parametros] para o JDBC."
  [predicado]
  [(str/replace predicado "$1" "?") (count (re-seq #"\$1" predicado))])

(defn colunas
  "As colunas da tabela, na ordem: [{:coluna :tipo :nulo? :comentario}] — o dicionario de dados."
  [conectavel t]
  (mapv (fn [r] (-> r (assoc :nulo? (boolean (:nulo r))) (dissoc :nulo)))
        (jdbc/execute! conectavel
                       [(str "SELECT a.attname::text AS coluna, format_type(a.atttypid, a.atttypmod) AS tipo,
                                     NOT a.attnotnull AS nulo, col_description(a.attrelid, a.attnum) AS comentario
                                FROM pg_attribute a
                               WHERE a.attrelid = ?::regclass AND a.attnum > 0 AND NOT a.attisdropped
                               ORDER BY a.attnum")
                        (tabela-sql t)]
                       opts)))

(defn chave-primaria
  "As colunas da PK (para ordenar a exportacao de forma estavel), ou []."
  [conectavel t]
  (mapv :coluna
        (jdbc/execute! conectavel
                       ["SELECT a.attname::text AS coluna
                           FROM pg_index i
                           JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)
                          WHERE i.indrelid = ?::regclass AND i.indisprimary
                          ORDER BY array_position(i.indkey::int2[], a.attnum)"
                        (tabela-sql t)]
                       opts)))

(defn colunas-que-apontam-para
  "As colunas (FK de uma coluna) da tabela `t` que apontam para `alvo` (ex.: \"identidade.identidade\")."
  [conectavel t alvo]
  (mapv :coluna
        (jdbc/execute! conectavel
                       ["SELECT a.attname::text AS coluna
                           FROM pg_constraint c
                           JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                          WHERE c.contype = 'f' AND c.conparentid = 0 AND cardinality(c.conkey) = 1
                            AND c.conrelid = ?::regclass AND c.confrelid = to_regclass(?)"
                        (tabela-sql t) alvo]
                       opts)))
