(ns oplenario.motor.guarda-test
  "Save-time (Inv.4 / disciplina 5): `motor/validar-guarda` tira a falha do GUARD de uma transicao
  de tramitacao do caminho critico — um regimento com guard mal-escrito e' REJEITADO na configuracao,
  nunca no meio de um fluxo (o clerk tentando despachar uma proposicao). F3.3b cobre a validacao
  SINTATICA (parse). Type-check estatico completo contra o vocabulario de tramitacao (registros
  proposicao/contexto) e' carry — depende da catalogacao do eixo C (analogo a §22.7.5)."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.motor.api :as motor]))

(deftest guard-ausente-e-valido
  (is (= "VALIDA" (:status (motor/validar-guarda nil))) "guard nil = sempre passa = valido")
  (is (= "VALIDA" (:status (motor/validar-guarda ""))) "guard vazio = ausente = valido")
  (is (= "VALIDA" (:status (motor/validar-guarda "   "))) "guard so-espaco = ausente = valido"))

(deftest guard-bem-formado-e-valido
  (is (= "VALIDA" (:status (motor/validar-guarda "verdadeiro"))) "literal booleano parseia")
  (is (= "VALIDA" (:status (motor/validar-guarda "proposicao.estado == \"protocolada\"")))
      "comparacao de campo parseia")
  (is (= "VALIDA" (:status (motor/validar-guarda "nao falso ou verdadeiro e verdadeiro")))
      "expressao booleana composta parseia"))

(deftest guard-mal-formado-e-rejeitado
  (let [r (motor/validar-guarda "( verdadeiro")]
    (is (= "INVALIDA" (:status r)) "parentese sem fechar e' rejeitado")
    (is (seq (:erros r)) "traz a causa do erro de sintaxe"))
  (is (= "INVALIDA" (:status (motor/validar-guarda "ou"))) "operador sozinho e' rejeitado")
  (is (= "INVALIDA" (:status (motor/validar-guarda "verdadeiro verdadeiro")))
      "sobra de tokens apos a expressao e' rejeitada"))
