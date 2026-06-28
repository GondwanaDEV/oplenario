(ns oplenario.kernel.db-util
  "Helpers de data layer compartilhados (kernel — nao importa modulo, §22.10). A conversao da linha do
  next.jdbc (chaves namespaced snake :tabela/coluna_snake) para o mapa de dominio (kebab, sem namespace)
  + coercao jsonb (PGobject <-> dado Clojure)."
  (:require [clojure.string :as str]
            [jsonista.core :as json])
  (:import (org.postgresql.util PGobject)))

(set! *warn-on-reflection* true)

(defn linha->kebab
  "Linha next.jdbc (:tabela/coluna_snake -> val) -> mapa de dominio {:coluna-kebab val}; descarta o ns."
  [linha]
  (when linha
    (update-keys linha (fn [k] (keyword (str/replace (name k) \_ \-))))))

(defn linhas->kebab [linhas] (mapv linha->kebab linhas))

;; ---- jsonb <-> dado (compartilhado; outbox tem copia propria pre-datavel) ----
(defn ->jsonb
  "Dado Clojure -> PGobject jsonb (para INSERT/UPDATE de coluna jsonb). NOTA: `(->jsonb nil)` produz o
  literal JSON `null` (PGobject 'null'), NAO SQL NULL — para gravar SQL NULL passe `nil` direto (sem
  envelopar). Os callers do motor ou garantem nao-nil (forma_compilada NOT NULL) ou usam `(or x {})`
  (parametros_tenant), entao o caso `null` nao alcanca o banco."
  ^PGobject [x]
  (doto (PGobject.) (.setType "jsonb") (.setValue (json/write-value-as-string x))))

(defn jsonb->kw
  "Coluna jsonb (PGobject) -> dado com chaves KEYWORD. Usar p/ estruturas internas (ex.: forma_compilada,
  assinatura). NOTA F2.3: o round-trip de AST com VALORES keyword (ex.: :t :lit) nao sobrevive a JSON —
  a persistencia real da forma_compilada normaliza tags p/ string (carry de F2.3)."
  [v]
  (when (instance? PGobject v)
    (json/read-value (.getValue ^PGobject v) json/keyword-keys-object-mapper)))

(defn jsonb->str
  "Coluna jsonb (PGobject) -> dado com chaves STRING. Usar p/ parametros_tenant — a chave casa com a
  string literal da DSL (ex.: parametro_tenant(\"prazo_publicacao_ato_dias\"))."
  [v]
  (when (instance? PGobject v)
    (json/read-value (.getValue ^PGobject v))))
