(ns oplenario.transparencia.acompanhamento-adapters-out-test
  "UNIT (puro, sem DB) — mesmo achado IMPORTANTE de materia-adapters-out-test, sitios (c)/(d) da frente
  'truncamento-familia': `meus->wire` tinha `(or acompanhamentos-total 0)`, desarmando a trava do schema."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.transparencia.adapters.out.acompanhamento :as adapters]))

(deftest acompanhamentos-total-ausente-lanca-nao-vira-zero-silencioso
  (is (thrown? clojure.lang.ExceptionInfo (adapters/meus->wire {:acompanhamentos []}))
      "sem :acompanhamentos-total no mapa de dominio, o adapter tem de lancar — nao coagir a 0"))
