(ns oplenario.compliance.logic-test
  (:require [clojure.test :refer [deftest is]]))

;; UNITÁRIO: logic puro — ciclo da obrigação (enum fixo) + costura remessa->obrigação (aceita CUMPRE; rejeição não)
