(ns oplenario.motor.nucleo
  "Núcleo de expressão A2 (§22.7 Eixo A dec. 1): lexer + AST + parser, mais o loader
  do envelope de compliance (§22.7.5). 'Sem loops, sem variáveis mutáveis, sem efeitos
  colaterais' — herdado de §22.4 eixo C.

  AST = mapas de dados Clojure (regra é dado). Loader proprietário (não YAML de
  prateleira) por FIDELIDADE: campos de expressão ficam STRING CRUA p/ o parser de
  expressão (um parser YAML misparsearia `ato.tipo in { resolucao, ... }`)."
  (:require [clojure.string :as str]))

(defn erro-sintaxe [msg] (ex-info msg {:erro :sintaxe}))

;; ===========================================================================
;; Lexer  — tokens [tipo valor]; tipos: :str :int :ident :op :lp :rp :lb :rb :comma :dot :eof
;; ===========================================================================
(def ^:private ops2 #{">=" "<=" "==" "!="})
(def ^:private op1 #{\> \< \* \+ \-})
(def ^:private simples {\( :lp \) :rp \{ :lb \} :rb \, :comma \. :dot})

(defn tokenizar [s]
  (let [n (count s)]
    (loop [i 0 toks []]
      (if (>= i n)
        (conj toks [:eof nil])
        (let [c (.charAt s i)
              two (when (<= (+ i 2) n) (subs s i (+ i 2)))]
          (cond
            (Character/isWhitespace c) (recur (inc i) toks)

            (= c \")
            (let [j (loop [j (inc i)]
                      (cond (>= j n) j
                            (= (.charAt s j) \") j
                            :else (recur (inc j))))]
              (when (>= j n) (throw (erro-sintaxe (str "string não terminada em: " (pr-str s)))))
              (recur (inc j) (conj toks [:str (subs s (inc i) j)])))

            (Character/isDigit c)
            (let [j (loop [j i] (if (and (< j n) (Character/isDigit (.charAt s j))) (recur (inc j)) j))]
              (recur j (conj toks [:int (Long/parseLong (subs s i j))])))

            (or (Character/isLetter c) (= c \_))
            (let [j (loop [j i] (if (and (< j n) (or (Character/isLetterOrDigit (.charAt s j)) (= (.charAt s j) \_)))
                                  (recur (inc j)) j))]
              (recur j (conj toks [:ident (subs s i j)])))

            (and two (contains? ops2 two)) (recur (+ i 2) (conj toks [:op two]))
            (contains? op1 c) (recur (inc i) (conj toks [:op (str c)]))

            :else
            (if-let [tk (get simples c)]
              (recur (inc i) (conj toks [tk (str c)]))
              (throw (erro-sintaxe (str "caractere inesperado " (pr-str c) " em: " (pr-str s)))))))))))

;; ===========================================================================
;; Parser (descida recursiva; precedência: ou < e < nao < comparação < +,- < *)
;; estado = atom {:toks :i}
;; ===========================================================================
(def ^:private bool-lit {"verdadeiro" true "falso" false})
(def ^:private reservadas #{"in" "e" "ou" "nao"})
(def ^:private comparadores #{">" ">=" "<" "<=" "==" "!="})

(declare expr-p ou-p e-p nao-p comparacao-p soma-p produto-p primario-p args-p conjunto-p)

(defn- peek-t [p] (nth (:toks @p) (:i @p)))
(defn- eh?
  ([p tipo] (= (first (peek-t p)) tipo))
  ([p tipo valor] (let [[t v] (peek-t p)] (and (= t tipo) (= v valor)))))
(defn- avancar! [p] (let [t (peek-t p)] (swap! p update :i inc) t))
(defn- esperar! [p tipo]
  (let [[t v] (peek-t p)]
    (if (not= t tipo)
      (throw (erro-sintaxe (str "esperava " tipo ", veio " t "(" (pr-str v) ")")))
      (do (swap! p update :i inc) v))))

(defn- binop [op a b] {:t :binop :op op :esq a :dir b})

(defn- expr-p [p] (ou-p p))

(defn- ou-p [p]
  (loop [e (e-p p)] (if (eh? p :ident "ou") (do (avancar! p) (recur (binop "ou" e (e-p p)))) e)))

(defn- e-p [p]
  (loop [e (nao-p p)] (if (eh? p :ident "e") (do (avancar! p) (recur (binop "e" e (nao-p p)))) e)))

(defn- nao-p [p]
  (if (eh? p :ident "nao")
    (do (avancar! p) {:t :unop :op "nao" :operando (nao-p p)})
    (comparacao-p p)))

(defn- comparacao-p [p]
  (let [e (soma-p p)
        [t v] (peek-t p)]
    (if (or (and (= t :op) (contains? comparadores v)) (and (= t :ident) (= v "in")))
      (do (avancar! p) (binop v e (soma-p p)))
      e)))

(defn- soma-p [p]
  (loop [e (produto-p p)]
    (if (or (eh? p :op "+") (eh? p :op "-"))
      (let [op (second (avancar! p))] (recur (binop op e (produto-p p))))
      e)))

(defn- produto-p [p]
  (loop [e (primario-p p)]
    (if (eh? p :op "*") (do (avancar! p) (recur (binop "*" e (primario-p p)))) e)))

(defn- primario-p [p]
  (let [[t v] (peek-t p)]
    (cond
      (= t :int) (do (avancar! p) {:t :lit :valor v :tipo-lit "Inteiro"})
      (= t :str) (do (avancar! p) {:t :lit :valor v :tipo-lit "Texto"})
      (= t :lb) (conjunto-p p)
      (= t :lp) (do (avancar! p) (let [e (expr-p p)] (esperar! p :rp) e))
      (= t :ident)
      (do (avancar! p)
          (cond
            (contains? bool-lit v) {:t :lit :valor (get bool-lit v) :tipo-lit "Booleano"}
            (contains? reservadas v) (throw (erro-sintaxe (str "palavra reservada " (pr-str v) " fora de lugar")))
            :else
            (let [no0 (if (eh? p :lp) {:t :chamada :nome v :args (args-p p)} {:t :ident :nome v})]
              (loop [no no0]
                (if (eh? p :dot)
                  (do (avancar! p) (recur {:t :campo :obj no :campo (esperar! p :ident)}))
                  no)))))
      :else (throw (erro-sintaxe (str "token inesperado " t "(" (pr-str v) ")"))))))

(defn- args-p [p]
  (esperar! p :lp)
  (let [args (if (eh? p :rp)
               []
               (loop [acc [(expr-p p)]]
                 (if (eh? p :comma) (do (avancar! p) (recur (conj acc (expr-p p)))) acc)))]
    (esperar! p :rp)
    args))

(defn- conjunto-p [p]
  (esperar! p :lb)
  (let [els (if (eh? p :rb)
              []
              (loop [acc [(expr-p p)]]
                (if (eh? p :comma) (do (avancar! p) (recur (conj acc (expr-p p)))) acc)))]
    (esperar! p :rb)
    {:t :conjunto-lit :elementos els}))

(defn expr->fonte
  "Renderiza um nó do AST de volta para fonte DSL legível. Existe para as MENSAGENS de erro: o AST não
  guarda posição no texto original (o lexer descarta offsets), então a única forma de dizer ao autor do
  rito QUAL subexpressão o runtime recusou é reimprimi-la. Sem isto, quem escreve
  `parecer.favoravel e prazo_vigente(...) > hoje() e nao vetado` recebe 'não avaliou para booleano'
  sobre a expressão inteira e não sabe onde olhar.

  NÃO é um round-trip fiel — parênteses redundantes do original somem, e `binop` sempre imprime os seus
  (o que preserva o SENTIDO, que é o que a mensagem precisa)."
  [no]
  (case (:t no)
    :lit (case (:tipo-lit no)
           "Texto" (str \" (:valor no) \")
           "Booleano" (if (:valor no) "verdadeiro" "falso")
           (str (:valor no)))
    :ident (:nome no)
    :campo (str (expr->fonte (:obj no)) "." (:campo no))
    :conjunto-lit (str "{" (str/join ", " (map expr->fonte (:elementos no))) "}")
    :chamada (str (:nome no) "(" (str/join ", " (map expr->fonte (:args no))) ")")
    :unop (str (:op no) " " (expr->fonte (:operando no)))
    :binop (str "(" (expr->fonte (:esq no)) " " (:op no) " " (expr->fonte (:dir no)) ")")
    (pr-str no)))

(defn identificadores-raiz
  "Devolve o conjunto dos identificadores-RAIZ que `no` (um nó de `parse-expr`) referencia — o
  mecanismo GENÉRICO por baixo de uma allowlist de vocabulário. O núcleo não conhece vocabulário
  de ninguém (§22.10: kernel/motor nunca importa um módulo); ele só sabe apontar QUAIS raízes uma
  expressão usa — quem julga se uma raiz é permitida é de quem chama (o módulo dono da coluna).

  Percorre os 7 `:t` de `parse-expr`. Duas regras que NÃO são 'recursa em tudo':
    - `:campo` NÃO contribui com o nome do campo — só recursa no `:obj` (`proposicao.estado`
      referencia `proposicao`, não `estado`: o campo em si não é vocabulário do amb).
    - `:chamada` NÃO contribui com o seu `:nome` — é STRING do nome do FATO, resolvida pelo
      `:resolver` do RegistroFatos, nunca pelo `amb`. Mas RECURSA em `:args`: `é_presidente(x.y)`
      tem que acusar `x`."
  [no]
  (case (:t no)
    :lit #{}
    :ident #{(:nome no)}
    :campo (identificadores-raiz (:obj no))
    :conjunto-lit (reduce into #{} (map identificadores-raiz (:elementos no)))
    :chamada (reduce into #{} (map identificadores-raiz (:args no)))
    :unop (identificadores-raiz (:operando no))
    :binop (into (identificadores-raiz (:esq no)) (identificadores-raiz (:dir no)))
    (throw (erro-sintaxe (str "nó AST desconhecido: " (pr-str no))))))

(defn parse-expr [s]
  (let [p (atom {:toks (tokenizar s) :i 0})
        no (expr-p p)]
    (when-not (eh? p :eof)
      (let [[t v] (peek-t p)]
        (throw (erro-sintaxe (str "sobra de tokens após a expressão: " t "(" (pr-str v) ")")))))
    no))

;; ===========================================================================
;; Loader do envelope de compliance (§22.7.5) — devolve um mapa-Envelope.
;; ===========================================================================
(defn- sem-comentario [linha]
  (loop [chs (seq linha) em-str false out []]
    (if (empty? chs)
      (str/trimr (apply str out))
      (let [ch (first chs)]
        (cond
          (= ch \") (recur (rest chs) (not em-str) (conj out ch))
          (and (= ch \#) (not em-str)) (str/trimr (apply str out))
          :else (recur (rest chs) em-str (conj out ch)))))))

(defn- split-primeiro-colon [s]
  (let [idx (str/index-of s ":")]
    (if idx [(subs s 0 idx) (subs s (inc idx))] [s ""])))

(defn- parse-flow-map [valor]
  (let [v (str/trim valor)
        v (cond-> v (str/starts-with? v "{") (subs 1))
        v (cond-> v (str/ends-with? v "}") (->> (drop-last) (apply str)))]
    (into {} (for [parte (str/split v #",")
                   :let [parte (str/trim parte)]
                   :when (not (str/blank? parte))]
               (let [[k tnome] (str/split parte #":" 2)]
                 [(str/trim k) (str/trim tnome)])))))

(defn- finalizar [bruto]
  (let [params (if (get bruto "parametros") (parse-flow-map (get bruto "parametros")) {})
        prazo-raw (get bruto "prazo")
        prazo (cond
                (map? prazo-raw) prazo-raw
                (and (nil? prazo-raw) (not (contains? bruto "prazo"))) nil
                :else {:__malformado__ (str prazo-raw)})]
    {:template (get bruto "template" "")
     :contexto (get bruto "contexto" "")
     :dominio (get bruto "dominio" "")
     :parametros params
     :aplica-quando (get bruto "aplica_quando")
     :exige (get bruto "exige")
     :prazo prazo
     :severidade (get bruto "severidade")
     :referencia-normativa (get bruto "referencia_normativa")
     :bruto bruto}))

(defn carregar-envelope [texto]
  (let [linhas (mapv sem-comentario (str/split-lines texto))
        n (count linhas)]
    (loop [i 0 bruto {}]
      (if (>= i n)
        (finalizar bruto)
        (let [raw (nth linhas i)]
          (if (or (str/blank? raw) (= \space (.charAt raw 0)))
            (recur (inc i) bruto)
            (let [[chave valor] (split-primeiro-colon raw)
                  chave (str/trim chave) valor (str/trim valor)]
              (when (str/blank? chave)
                (throw (erro-sintaxe (str "chave vazia no envelope, linha: " (pr-str raw)))))
              (if (= valor "")
                (let [[filhos j]
                      (loop [j (inc i) filhos {}]
                        (if (and (< j n) (str/starts-with? (nth linhas j) " ") (not (str/blank? (nth linhas j))))
                          (let [[ck cv] (split-primeiro-colon (str/trim (nth linhas j)))]
                            (recur (inc j) (assoc filhos (str/trim ck) (str/trim cv))))
                          [filhos j]))]
                  (recur j (assoc bruto chave (if (seq filhos) filhos nil))))
                (recur (inc i) (assoc bruto chave valor))))))))))
