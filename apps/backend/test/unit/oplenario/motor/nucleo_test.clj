(ns oplenario.motor.nucleo-test
  "Coletor de identificadores-raiz (`nuc/identificadores-raiz`) — o mecanismo GENERICO que a
  allowlist de vocabulario (motor/api :: validar-guarda opts) usa por baixo. O motor nao conhece
  vocabulario de ninguem (§22.10: kernel/motor nunca importa um modulo); ele so' sabe percorrer o
  AST e dizer quais identificadores-raiz uma expressao referencia — quem julga o que e' PERMITIDO
  e' de quem chama (o modulo, via allowlist declarada por coluna).

  O risco central e' um identificador ESCONDIDO num `:t` que o walk esquece de visitar — um caso
  por esconderijo, cada um com o identificador-alvo enterrado num lugar diferente do AST."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.motor.nucleo :as nuc]))

(defn- ids [fonte] (nuc/identificadores-raiz (nuc/parse-expr fonte)))

;; ---------------------------------------------------------------------------
;; Um esconderijo por :t — 7 valores de :t, cada um capaz de esconder um :ident
;; ---------------------------------------------------------------------------
(deftest ident-raiz-nua
  (is (= #{"alegado"} (ids "alegado")) ":ident na raiz contribui com :nome"))

(deftest ident-sob-campo
  (is (= #{"alegado"} (ids "alegado.parecer"))
      ":campo NAO contribui com o nome do campo — so' recursa no :obj")
  (is (= #{"proposicao"} (ids "proposicao.estado"))
      "campo encadeado tambem so' acusa a raiz"))

(deftest ident-dentro-de-args-de-chamada
  (is (= #{"alegado"} (ids "é_presidente(alegado.x)"))
      ":chamada NAO contribui com :nome (e' fato, resolve pelo :resolver) mas RECURSA em :args")
  (is (= #{} (ids "populacao()")) "chamada sem args nao acusa nada"))

(deftest ident-dentro-de-conjunto-lit
  (is (= #{"alegado"} (ids "alegado.tipo in { \"a\", \"b\" }"))
      ":conjunto-lit recursa em :elementos — aqui via o :esq do :binop 'in'; conjunto sozinho abaixo"))

(deftest ident-em-conjunto-lit-isolado
  ;; um :conjunto-lit cujo elemento carrega o identificador (nao so' literais)
  (is (= #{"alegado"} (ids "{ alegado.x }")) ":conjunto-lit recursa em cada :elementos"))

(deftest ident-em-binop-esq-e-dir
  (is (= #{"alegado" "proposicao"} (ids "alegado.x == proposicao.y"))
      ":binop recursa em :esq E :dir")
  (is (= #{"alegado"} (ids "alegado.x == alegado.y"))
      "mesmo identificador dos dois lados so' aparece uma vez (conjunto)"))

(deftest ident-sob-unop
  (is (= #{"alegado"} (ids "nao alegado.aprovado")) ":unop recursa em :operando"))

(deftest ident-aninhado-binop-dentro-de-args-de-chamada
  (is (= #{"alegado" "proposicao"} (ids "é_presidente(alegado.x == proposicao.y)"))
      "aninhado: :binop dentro de um :arg de :chamada — nenhum nivel intermediario pode comer o achado"))

;; ---------------------------------------------------------------------------
;; Positivos / negativos de forma (o que NAO deve ser tratado como identificador)
;; ---------------------------------------------------------------------------
(deftest nome-de-fato-nao-e-identificador
  (is (not (contains? (ids "é_presidente(alegado.x)") "é_presidente"))
      "o :nome de uma :chamada e' STRING do fato, resolve pelo :resolver — nao pelo amb"))

(deftest nome-de-campo-nao-e-identificador
  (is (not (contains? (ids "proposicao.estado") "estado"))
      "o :campo em si nao entra — so' a raiz do :obj"))

(deftest literal-sozinho-nao-acusa-nada
  (is (= #{} (ids "verdadeiro")))
  (is (= #{} (ids "42")))
  (is (= #{} (ids "\"x\""))))

(deftest expressao-so-com-vocabulario-permitido
  (is (= #{"proposicao" "contexto"} (ids "proposicao.estado == \"protocolada\" e contexto.ano > 2020"))))
