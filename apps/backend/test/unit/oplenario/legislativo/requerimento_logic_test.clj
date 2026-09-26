(ns oplenario.legislativo.requerimento-logic-test
  "UNIT (puro): fatia 2a — o requerimento que o vereador redige a partir de um modelo da Casa. Quais campos o
  formulario pede (os placeholders do modelo MENOS os que o sistema preenche), como os dados se juntam (o
  sistema vence o cliente nos campos automaticos — anti-forja de autoria/data) e a data por extenso."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.legislativo.logic :as logic])
  (:import (java.time LocalDate)))

(def ^:private modelo
  "REQUERIMENTO\n\n{{vereador}}, no uso de suas atribuicoes, requer a {{ destinatario }} informacoes sobre {{assunto}}.\n\nFortaleza, {{data}}.\n\n{{vereador}}")

(deftest placeholders-em-ordem-sem-repeticao
  (is (= ["vereador" "destinatario" "assunto" "data"] (logic/placeholders-do-template modelo))
      "ordem de primeira aparicao, espacos dentro das chaves ignorados, repeticao contada uma vez")
  (is (= [] (logic/placeholders-do-template "texto fixo, sem campo")))
  (is (= [] (logic/placeholders-do-template nil))))

(deftest campos-que-o-vereador-preenche
  (is (= ["destinatario" "assunto"] (logic/campos-do-requerimento modelo))
      "vereador e data sao do sistema: o formulario nao os pede"))

(deftest data-por-extenso
  (is (= "26 de setembro de 2026" (logic/data-por-extenso (LocalDate/of 2026 9 26))))
  (is (= "1º de março de 2027" (logic/data-por-extenso (LocalDate/of 2027 3 1)))
      "primeiro dia do mes se escreve com ordinal, como nos atos oficiais"))

(deftest dados-do-requerimento-o-sistema-vence
  (let [d (logic/dados-do-requerimento {"assunto" "a obra X" "vereador" "Outra Pessoa" "data" "1900"}
                                       {:nome-vereador "Ana Prado" :hoje (LocalDate/of 2026 9 26)})]
    (is (= "a obra X" (get d "assunto")))
    (is (= "Ana Prado" (get d "vereador")) "o nome do autor vem do login, nunca do corpo")
    (is (= "26 de setembro de 2026" (get d "data")) "a data vem do relogio do servidor, nunca do corpo")))

(deftest texto-do-requerimento
  (is (= "REQUERIMENTO\n\nAna Prado, no uso de suas atribuicoes, requer a Secretaria de Obras informacoes sobre a obra X.\n\nFortaleza, 26 de setembro de 2026.\n\nAna Prado"
         (logic/renderizar-documento modelo
                                     (logic/dados-do-requerimento {"destinatario" "Secretaria de Obras" "assunto" "a obra X"}
                                                                  {:nome-vereador "Ana Prado" :hoje (LocalDate/of 2026 9 26)}))))
  (is (thrown? Exception
               (logic/renderizar-documento modelo
                                           (logic/dados-do-requerimento {"assunto" "a obra X"}
                                                                        {:nome-vereador "Ana Prado" :hoje (LocalDate/of 2026 9 26)})))
      "campo do modelo sem valor -> fail-closed (um ato oficial nao sai com lacuna)"))
