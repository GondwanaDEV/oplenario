(ns oplenario.motor.operadores-logicos-test
  "O ACHADO (revisao adversarial da borda de tramitacao, fatia 5): `exigir-booleano!` (motor/api) fez o
  guard e a politica falharem FECHADO quando a expressao nao avalia para booleano — mas ele so' olha o
  no' de TOPO, e `e`/`ou`/`nao` DEVOLVEM booleano mesmo quando COAGIRAM um operando truthy la' dentro:

    contexto.x            (String \"nao\")  -> lanca   (o topo e' a String)
    falso ou contexto.x                     -> true    ERRADO (o `ou` devolveu booleano)
    verdadeiro e contexto.x                 -> true    ERRADO (o `e` devolveu booleano)

  Basta UM `e`/`ou` na expressao para o fail-closed evaporar, e os dois seams que usam esses operadores
  sao o guard de tramitacao (`guarda-dsl`) e a politica de autorizacao do F2 (`politica-dsl`, o
  `policy.check`). Um regimento ou uma politica com um unico `e` voltava a aceitar string/UUID/numero
  vindo do cliente como 'verdadeiro'.

  O conserto e' de LINGUAGEM, nao de seam: os operadores logicos EXIGEM operandos booleanos em vez de
  coagi-los. Nao e' regra nova — `verificador/inf-binop` ja' declara `operador 'e' exige Booleano` no
  type-check do save time; o runtime e' que divergia da propria especificacao de tipos. E onde nao ha'
  type-check estatico (guard de tramitacao: `validar-guarda` e' so' sintatico, [CARRY] do eixo C) a
  checagem de runtime e' a UNICA rede."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.stuartsierra.component :as component]
            [oplenario.motor.api :as motor]
            [oplenario.motor.components.registro-fatos :as rf]
            [oplenario.motor.runtime :as rt])
  (:import (java.time LocalDate)))

(def ^:private AMB
  "O ambiente do achado: um campo do corpo do cliente que NAO e' booleano. \"nao\" e' truthy em Clojure —
  a materia avancava com o parecer dizendo NAO."
  {"contexto" {:x "nao" :n 7 :ok true :vazio nil}})

(defn- ev [src] (rt/eval-expr src AMB (rt/estado) (rt/ldate 2026 6 19)))

(defn- erro-de [src]
  (try (ev src) ::nao-lancou
       (catch clojure.lang.ExceptionInfo e e)))

;; ==============================================================================================
;; A tabela do achado, no avaliador
;; ==============================================================================================

(deftest operadores-logicos-nao-coagem-operando-truthy
  (testing "o no' de topo sozinho ja' falhava fechado nos seams — aqui so' se confirma o VALOR cru"
    (is (= "nao" (ev "contexto.x")) "o avaliador devolve a String; quem fecha o topo e' exigir-booleano!"))

  (testing "`ou` com operando direito nao-booleano LANCA em vez de devolver true"
    (let [e (erro-de "falso ou contexto.x")]
      (is (instance? clojure.lang.ExceptionInfo e) "antes devolvia true — a coercao truthy sobrevivia dentro do `ou`")
      (is (= :runtime (:erro (ex-data e))) "mesma tag de fato-sem-fn: a borda traduz em 500 NOMEADO / negacao")))

  (testing "`e` com operando direito nao-booleano LANCA em vez de devolver true"
    (let [e (erro-de "verdadeiro e contexto.x")]
      (is (instance? clojure.lang.ExceptionInfo e) "antes devolvia true")
      (is (= :runtime (:erro (ex-data e))))))

  (testing "`nao` sobre nao-booleano LANCA em vez de devolver false"
    (let [e (erro-de "nao contexto.x")]
      (is (instance? clojure.lang.ExceptionInfo e) "antes `(not \"nao\")` devolvia false — negacao SILENCIOSA")
      (is (= :runtime (:erro (ex-data e))))))

  (testing "nao e' so' String: numero e nil (campo ausente) tambem sao recusados"
    (is (instance? clojure.lang.ExceptionInfo (erro-de "verdadeiro e contexto.n")) "Inteiro nao e' Booleano")
    (is (instance? clojure.lang.ExceptionInfo (erro-de "verdadeiro e contexto.ausente"))
        "campo ausente -> nil: virava `false` em silencio, respondendo 'a Casa nao permite' a uma pergunta
         que ninguem conseguiu fazer")))

(deftest a-mensagem-nomeia-a-SUBEXPRESSAO-culpada
  ;; Sem isto, quem escreve o rito recebe 'nao avaliou para booleano' sobre uma expressao de 5 termos
  ;; e nao sabe onde olhar.
  ;; `contexto.n > 30` e' FALSO de proposito: e' o que faz o `ou` chegar ao operando culpado. Com
  ;; `> 3` o `ou` curto-circuitaria em true e `contexto.x` nem seria lido — curto-circuito e tipagem
  ;; sao a MESMA coisa aqui: o que nao e' avaliado nao e' checado.
  (let [e (erro-de "contexto.ok e (contexto.n > 30 ou contexto.x)")
        msg (ex-message e)]
    (is (instance? clojure.lang.ExceptionInfo e))
    (is (str/includes? msg "contexto.x")
        (str "a mensagem tem de nomear o operando culpado, nao a expressao inteira: " (pr-str msg)))
    (is (= "contexto.x" (:subexpressao (ex-data e)))
        "e a ex-data traz a subexpressao em campo proprio (legivel por maquina)")
    (is (= "ou" (:op (ex-data e))) "e QUAL operador a recusou")
    (is (str/includes? msg "String") (str "e o tipo que veio: " (pr-str msg)))))

(deftest aninhamento-de-dois-niveis-tambem-fecha
  (is (instance? clojure.lang.ExceptionInfo (erro-de "verdadeiro e (verdadeiro e contexto.x)"))
      "o `e` de fora recebe booleano do `e` de dentro — a checagem so' de topo passava aqui")
  (is (instance? clojure.lang.ExceptionInfo (erro-de "nao (falso ou contexto.x)"))
      "`nao` sobre um `ou` que coagiu la' dentro")
  (is (instance? clojure.lang.ExceptionInfo (erro-de "(contexto.x e verdadeiro) ou falso"))
      "o operando ESQUERDO tambem e' exigido booleano"))

;; ==============================================================================================
;; O caminho FELIZ e o curto-circuito continuam intactos (fail-closed que recusa tudo e' pane)
;; ==============================================================================================

(deftest booleano-de-verdade-continua-funcionando
  (is (false? (ev "verdadeiro e falso")))
  (is (true?  (ev "verdadeiro e verdadeiro")))
  (is (true?  (ev "falso ou verdadeiro")))
  (is (false? (ev "falso ou falso")))
  (is (true?  (ev "nao falso")))
  (is (true?  (ev "contexto.ok e nao falso")) "campo que E' booleano atravessa intacto")
  (is (true?  (ev "contexto.n > 3 e contexto.ok")) "comparacao devolve booleano de verdade"))

(deftest curto-circuito-preservado
  ;; Regra VALIDA com ramo morto que explodiria (`falso e prazo_vigente(...)`) nao pode passar a explodir.
  (is (false? (ev "falso e contexto.x"))
      "`e` com esquerdo FALSO nao toca o ramo direito — nem para checar o tipo dele (ramo morto e' morto)")
  (is (true? (ev "verdadeiro ou contexto.x"))
      "`ou` com esquerdo VERDADEIRO nao toca o ramo direito")
  (is (false? (ev "falso e prazo_vigente(\"X\",\"Y\",contexto)"))
      "o ramo morto classico (prazo inexistente) segue nao sendo avaliado"))

;; ==============================================================================================
;; Os DOIS seams que o achado atinge (a razao de o defeito ser critico)
;; ==============================================================================================

(defn- com-registro [f]
  (let [r (component/start (rf/registro-fatos {}))]
    (try (f r) (finally (component/stop r)))))

(deftest guarda-dsl-com-e-nao-abre-mais
  (com-registro
    (fn [registro]
      (let [g (motor/guarda-dsl {:registro registro :tx nil :ente-id (random-uuid)
                                 :expr "verdadeiro e alegado.parecer_favoravel"
                                 :agora (LocalDate/of 2026 6 19)})
            amb {"alegado" {:parecer_favoravel "nao"}}
            e (try (g amb) ::nao-lancou (catch clojure.lang.ExceptionInfo ex ex))]
        (is (instance? clojure.lang.ExceptionInfo e)
            "um unico `e` no guard devolvia o fail-closed da fatia 4 ao estado anterior: a materia avancava
             com o parecer dizendo NAO")
        (is (= :runtime (:erro (ex-data e))))
        (is (true? (g {"alegado" {:parecer_favoravel true}})) "booleano de verdade continua passando")
        (is (false? (g {"alegado" {:parecer_favoravel false}})) "e continua recusando por DOMINIO")))))

(deftest politica-dsl-com-ou-nao-permite-mais
  (com-registro
    (fn [registro]
      (let [p (motor/politica-dsl {:registro registro :tx nil
                                   :expr "falso ou ator.identidade"
                                   :agora (LocalDate/of 2026 6 19)})
            ator {:identidade (random-uuid) :ente-id (random-uuid)}
            e (try (p ator {}) ::nao-lancou (catch clojure.lang.ExceptionInfo ex ex))]
        (is (instance? clojure.lang.ExceptionInfo e)
            "o UUID do ator virava `true` dentro do `ou` — permissao concedida a qualquer um que tivesse id")
        (is (= :runtime (:erro (ex-data e))) "check! traduz lance -> negacao (quem nao decide, NEGA)")))))
