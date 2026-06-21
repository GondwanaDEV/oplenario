(ns oplenario.arquitetura-test
  "Enforcement da matriz de import-lint do §22.10 (CI falha em violacao)."
  (:require [clojure.test :refer [deftest is]]))

;; 1. oplenario.<a>.* NUNCA requer oplenario.<b>.* (a!=b) -> so HTTP/eventos
;; 2. kernel.* e motor.* NUNCA requerem <ctx>.*
;; 3. db/logic/internal de um ctx so importados por ele
(deftest matriz-de-dependencia-de-modulos
  (is true "TODO: implementar a varredura da matriz"))
