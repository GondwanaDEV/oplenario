(ns oplenario.encerramento.csv
  "HOST (§22.10) — o FORMATO ABERTO da exportacao da Casa (ADR-0018, Eixo 4.2 / 9.6): como um valor do Postgres vira
  um campo de CSV. PURO (sem banco), para que a regra seja testada sozinha e documentada uma vez no LEIA-ME.

  - CSV RFC 4180, UTF-8, separador `,`, fim de linha CRLF, cabecalho = nomes das colunas;
  - NULL = campo VAZIO sem aspas; texto vazio = `\"\"` (aspas) — os dois nao se confundem;
  - jsonb/json = o JSON como o banco guarda; arrays = JSON (`[\"a\",\"b\"]`);
  - bytea = base64 (RFC 4648, com padding);
  - timestamptz = instante ISO-8601 em UTC (`2026-10-02T14:33:20.770517Z`); date = `AAAA-MM-DD`;
  - uuid, numeros, booleanos (`true`/`false`) e os demais tipos = o texto do proprio Postgres."
  (:require [clojure.string :as str]
            [jsonista.core :as json])
  (:import (java.sql Array Date Time Timestamp)
           (java.time Instant LocalDate LocalDateTime OffsetDateTime)
           (java.util Base64 UUID)
           (org.postgresql.util PGobject)))

(set! *warn-on-reflection* true)

(declare ->texto)

(defn- elemento->json
  "Um elemento de array do Postgres -> valor que o JSON representa (numero e booleano continuam numero/booleano)."
  [x]
  (cond (nil? x) nil
        (or (number? x) (boolean? x)) x
        (instance? PGobject x) (let [^PGobject o x
                                     v (.getValue o)]
                                 (if (#{"json" "jsonb"} (.getType o)) (json/read-value v) v))
        :else (->texto x)))

(defn ->texto
  "Um valor lido do JDBC -> o texto do campo (ou nil = NULL). `tipo` (o nome do tipo no Postgres, ex. \"timestamptz\")
  so' distingue o `timestamp` sem zona, que o schema nao usa (lint de migrations) mas a exportacao nao pode errar."
  ([x] (->texto x nil))
  ([x tipo]
   (cond
     (nil? x) nil
     (string? x) x
     (bytes? x) (.encodeToString (Base64/getEncoder) ^bytes x)
     (instance? PGobject x) (.getValue ^PGobject x)
     (instance? Array x) (json/write-value-as-string (mapv elemento->json (seq (.getArray ^Array x))))
     (instance? Timestamp x) (if (= "timestamp" tipo)
                               (str (.toLocalDateTime ^Timestamp x))
                               (str (.toInstant ^Timestamp x)))
     (instance? Date x) (str (.toLocalDate ^Date x))
     (instance? Time x) (str (.toLocalTime ^Time x))
     (instance? OffsetDateTime x) (str (.toInstant ^OffsetDateTime x))
     (or (instance? Instant x) (instance? LocalDate x) (instance? LocalDateTime x) (instance? UUID x)) (str x)
     (boolean? x) (if x "true" "false")
     :else (str x))))

(defn campo
  "Texto (ou nil) -> o campo CSV escapado (RFC 4180)."
  ^String [s]
  (cond
    (nil? s) ""
    (or (= "" s) (re-find #"[\",\r\n]" s) (not= s (str/trim s))) (str \" (str/replace s "\"" "\"\"") \")
    :else s))

(defn linha
  "Os campos ja' em texto -> uma linha CSV terminada em CRLF."
  ^String [textos]
  (str (str/join "," (map campo textos)) "\r\n"))
