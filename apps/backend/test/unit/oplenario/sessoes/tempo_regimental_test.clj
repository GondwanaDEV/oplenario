(ns oplenario.sessoes.tempo-regimental-test
  "UNIT (puro): mig 0081 — qual tempo regimental vale para uma fala. Das linhas candidatas (a da fase e a
  generica, fase nil), a ESPECIFICA vence; sem nenhuma, nil = a fala corre sem limite."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.sessoes.logic :as logic]))

(deftest especifica-vence-generica
  (is (= 600 (logic/escolher-tempo-regimental [{:fase nil :segundos 300} {:fase "ordem_do_dia" :segundos 600}])))
  (is (= 600 (logic/escolher-tempo-regimental [{:fase "ordem_do_dia" :segundos 600} {:fase nil :segundos 300}]))
      "a ordem das linhas vindas do banco nao decide nada"))

(deftest so-generica-vale
  (is (= 300 (logic/escolher-tempo-regimental [{:fase nil :segundos 300}]))))

(deftest sem-linha-sem-limite
  (is (nil? (logic/escolher-tempo-regimental [])))
  (is (nil? (logic/escolher-tempo-regimental nil))))
