(ns oplenario.codegen.malli-ts
  "Codegen Malli -> TypeScript (primeiro corte, FE0 / §22.4 eixo 8). Ferramenta de BUILD (nao runtime):
  como o host/composicao, PODE ler os models/ dos modulos. Hand-rolled, zero-dep — o subconjunto de
  tipos dos models e' pequeno e bounded (mesma disciplina do motor-dsl). Mantem a fronteira core->TS
  (Inv.5) SINCRONIZADA: o schema Malli e' a fonte; o .ts e' derivado, nunca editado a mao.

  Cobertura do 1o corte: :map (+opts :closed), :uuid/:string/[:re ...] -> string, :int -> number,
  :boolean -> boolean, [:enum ...] -> uniao de literais, [:maybe X] -> X | null, {:optional true} ->
  campo?, [:fn ...] -> string (datas LocalDate/Instant viram ISO string no wire). Chaves kebab -> camelCase."
  (:require [clojure.string :as str]))

(defn- camel [k]
  (let [[h & t] (str/split (name k) #"-")]
    (apply str h (map str/capitalize t))))

(declare ts-tipo)

(defn- ts-enum [membros] (str/join " | " (map #(str \" % \") membros)))

(defn- ts-tipo [forma]
  (cond
    (keyword? forma) (case forma
                       (:uuid :string) "string"
                       :int "number"
                       (:double :number) "number"
                       :boolean "boolean"
                       "unknown")
    (vector? forma) (case (first forma)
                      :re "string"
                      :enum (ts-enum (rest forma))
                      :maybe (str (ts-tipo (second forma)) " | null")
                      :fn "string"                              ; LocalDate/Instant -> ISO string
                      :map "Record<string, unknown>"           ; map aninhado anonimo (1o corte)
                      "unknown")
    :else "unknown"))

(defn- entradas-de
  "Pula o :map e o mapa de opts de nivel ([:map {:closed true} & entradas])."
  [[_map maybe-opts & resto]]
  (if (map? maybe-opts) resto (cons maybe-opts resto)))

(defn- campo-ts [[k & r]]
  (let [opts   (when (map? (first r)) (first r))
        schema (if opts (second r) (first r))]
    (str "  " (camel k) (when (:optional opts) "?") ": " (ts-tipo schema) ";")))

(defn interface-ts
  "Uma [:map ...] Malli -> `export interface <Nome> { ... }`."
  [nome schema]
  (str "export interface " nome " {\n"
       (str/join "\n" (map campo-ts (entradas-de schema)))
       "\n}\n"))

(defn gerar
  "specs = mapa ordenado {\"Nome\" schema}. Devolve o conteudo .ts (banner + interfaces)."
  [specs]
  (str "// GERADO por oplenario.codegen.malli-ts a partir dos models Malli — NAO editar a mao.\n\n"
       (str/join "\n" (map (fn [[nome schema]] (interface-ts nome schema)) specs))))
