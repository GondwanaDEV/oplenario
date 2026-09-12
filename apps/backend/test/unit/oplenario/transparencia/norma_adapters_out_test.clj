(ns oplenario.transparencia.norma-adapters-out-test
  "UNIT (puro, sem DB) — mesmo achado IMPORTANTE de materia-adapters-out-test, sitio (c) da frente
  'truncamento-familia': `normas->wire` tinha `(or normas-total 0)`, desarmando a trava do schema."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.transparencia.adapters.out.norma :as adapters]))

(deftest normas-total-ausente-lanca-nao-vira-zero-silencioso
  (is (thrown? clojure.lang.ExceptionInfo (adapters/normas->wire {:normas []}))
      "sem :normas-total no mapa de dominio, o adapter tem de lancar (bug de servidor) — nao coagir a 0"))
