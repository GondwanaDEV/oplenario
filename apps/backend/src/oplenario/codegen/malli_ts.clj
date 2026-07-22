(ns oplenario.codegen.malli-ts
  "Codegen Malli -> TypeScript (primeiro corte, FE0 / §22.4 eixo 8). Ferramenta de BUILD (nao runtime):
  como o host/composicao, PODE ler os models/ dos modulos. Hand-rolled, zero-dep — o subconjunto de
  tipos dos models e' pequeno e bounded (mesma disciplina do motor-dsl). Mantem a fronteira core->TS
  (Inv.5) SINCRONIZADA: o schema Malli e' a fonte; o .ts e' derivado, nunca editado a mao.

  Cobertura: :map (+opts :closed) inline OU por REFERENCIA NOMEADA (quando a forma bate igualdade
  estrutural com uma entrada do manifesto), :uuid/:string/[:re ...] -> string, :int -> number,
  :boolean -> boolean, [:enum ...] -> uniao de literais, [:maybe X] -> X | null,
  [:sequential X]/[:vector X] -> X[],
  [:or A B ...] -> uniao TS 'A | B' (cada ramo resolvido recursivamente pela mesma referencia nomeada —
  achado da review de A5+A6: os campos de degradacao por card em MesaOut sao [:or <fechado>
  CardIndisponivelOut]), [:= v] -> tipo literal TS (sentinel `{:indisponivel true}`), {:optional true} ->
  campo?, [:fn ...] -> string (datas LocalDate/Instant viram ISO string no wire). Chaves kebab -> camelCase."
  (:require [clojure.string :as str]))

(defn- camel [k]
  (let [[h & t] (str/split (name k) #"-")]
    (apply str h (map str/capitalize t))))

(declare ts-tipo)

(defn- ts-enum [membros] (str/join " | " (map #(str \" % \") membros)))

(defn- ts-tipo
  "`nome-por-schema` = {schema-VALOR -> \"NomeDaInterface\"} (igualdade estrutural, nao identidade —
  schemas Malli sao dados literais). Um :map/[:map ...] cuja forma bate EXATAMENTE com uma entrada do
  manifesto emite o NOME (referencia de tipo), preservando a estrutura entre modulos sem reprojetar; o
  que nao bate cai em 'Record<string, unknown>' (mapa opaco, mesmo comportamento do 1o corte)."
  [nome-por-schema forma]
  (cond
    (keyword? forma) (case forma
                       (:uuid :string) "string"
                       :int "number"
                       (:double :number) "number"
                       :boolean "boolean"
                       :map "Record<string, unknown>"
                       "unknown")
    (vector? forma)
    (if-let [nome (get nome-por-schema forma)]
      nome
      (case (first forma)
        :re "string"
        :enum (ts-enum (rest forma))
        :maybe (str (ts-tipo nome-por-schema (second forma)) " | null")
        ;; :vector junto de :sequential (I-5 fatia 6): os wire/out do perfil publico do vereador usam
        ;; [:vector X], que ate' aqui caia no fallback "unknown" — e o proprio gate deste ns
        ;; (`nenhum campo caiu no fallback bare 'unknown'`) foi quem acusou, ao entrar no manifesto.
        (:sequential :vector) (str (ts-tipo nome-por-schema (second forma)) "[]")
        :or (str/join " | " (map (partial ts-tipo nome-por-schema) (rest forma)))
        := (pr-str (second forma))                 ; [:= true] -> tipo literal TS `true`
        :fn "string"                              ; LocalDate/Instant -> ISO string
        :map "Record<string, unknown>"           ; map aninhado anonimo, sem entrada no manifesto
        "unknown"))
    :else "unknown"))

(defn- entradas-de
  "Pula o :map e o mapa de opts de nivel ([:map {:closed true} & entradas])."
  [[_map maybe-opts & resto]]
  (if (map? maybe-opts) resto (cons maybe-opts resto)))

(defn- campo-ts [nome-por-schema [k & r]]
  (let [opts   (when (map? (first r)) (first r))
        schema (if opts (second r) (first r))]
    (str "  " (camel k) (when (:optional opts) "?") ": " (ts-tipo nome-por-schema schema) ";")))

(defn interface-ts
  "Uma [:map ...] Malli -> `export interface <Nome> { ... }`. Aridade 2 (sem mapa de referencias) preserva
  o comportamento do 1o corte (tudo aninhado vira Record<string, unknown>); aridade 3 permite referencias
  nomeadas entre interfaces do MESMO manifesto (`gerar` monta o mapa automaticamente)."
  ([nome schema] (interface-ts {} nome schema))
  ([nome-por-schema nome schema]
   (str "export interface " nome " {\n"
        (str/join "\n" (map (partial campo-ts nome-por-schema) (entradas-de schema)))
        "\n}\n")))

(defn gerar
  "specs = mapa ordenado {\"Nome\" schema}. Devolve o conteudo .ts (banner + interfaces), com referencias
  nomeadas resolvidas por igualdade estrutural entre as entradas de `specs`."
  [specs]
  (let [nome-por-schema (into {} (map (fn [[nome schema]] [schema nome]) specs))]
    (str "// GERADO por oplenario.codegen.malli-ts a partir dos models Malli — NAO editar a mao.\n\n"
         (str/join "\n" (map (fn [[nome schema]] (interface-ts nome-por-schema nome schema)) specs)))))
