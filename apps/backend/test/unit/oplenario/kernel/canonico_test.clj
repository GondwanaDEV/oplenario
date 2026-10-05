(ns oplenario.kernel.canonico-test
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.kernel.canonico :as canonico])
  (:import (java.time Instant LocalDate)))

(deftest a-mesma-forma-da-o-mesmo-hash
  (testing "ordem das chaves, em qualquer nivel, nao muda nada"
    (is (= (canonico/sha256 {:b 1 :a {:d [1 2] :c "x"}})
           (canonico/sha256 (array-map :a (array-map :c "x" :d [1 2]) :b 1)))))
  (testing "keyword e texto de mesmo nome sao a mesma chave"
    (is (= (canonico/json {:a 1}) (canonico/json {"a" 1}))))
  (testing "a ordem de um vetor importa"
    (is (not= (canonico/sha256 {:a [1 2]}) (canonico/sha256 {:a [2 1]})))))

(deftest tipos-que-nao-sao-json-viram-texto
  (let [u #uuid "5b0c1c9e-2f4e-4d7a-9d43-0f6f3c2a7e11"]
    (is (= "{\"d\":\"2026-10-05\",\"i\":\"2026-10-05T12:00:00Z\",\"k\":\"proposicao\",\"n\":null,\"u\":\"5b0c1c9e-2f4e-4d7a-9d43-0f6f3c2a7e11\"}"
           (canonico/json {:u u :i (Instant/parse "2026-10-05T12:00:00Z") :d (LocalDate/of 2026 10 5) :k :proposicao :n nil})))))

(deftest o-hash-e-sha256-hex
  (is (re-matches #"[0-9a-f]{64}" (canonico/sha256 {:a 1})))
  (is (= "015abd7f5cc57a2dd94b7590f04ad8084273905ee33ec5cebeae62276a97f862" (canonico/sha256 {:a 1}))
      "sha256 de {\"a\":1}"))
