(ns oplenario.kernel.malli-test
  "Specs-base reusaveis do kernel: identidade de tenant (Inv.1), referencia polimorfica
  (objeto_tipo,objeto_id) (§22.9 Eixo 2) e temporais. Os schema/ dos modulos compoem a partir daqui."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [oplenario.kernel.malli :as k])
  (:import (java.time Instant)))

(deftest ente-id-e-uuid
  (is (m/validate k/EnteId (random-uuid)) "ente_id valido e' UUID")
  (is (not (m/validate k/EnteId "nao-uuid")) "string nao e' ente_id"))

(deftest instante-e-java-instant
  (is (m/validate k/Instante (Instant/now)) "Instante aceita java.time.Instant")
  (is (not (m/validate k/Instante "ontem")) "string nao e' Instante"))

(deftest polimorfico-exige-tipo-e-id
  (is (m/validate k/Polimorfico {:objeto-tipo "proposicao" :objeto-id (random-uuid)})
      "ref polimorfica valida tem tipo + id")
  (is (not (m/validate k/Polimorfico {:objeto-tipo "proposicao"}))
      "sem objeto-id e' invalido")
  (is (not (m/validate k/Polimorfico {:objeto-tipo "proposicao" :objeto-id "x"}))
      "objeto-id deve ser UUID"))
