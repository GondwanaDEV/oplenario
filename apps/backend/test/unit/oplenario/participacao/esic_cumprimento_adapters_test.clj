(ns oplenario.participacao.esic-cumprimento-adapters-test
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [oplenario.participacao.adapters.out.esic-cumprimento :as adapters]
            [oplenario.participacao.wire.out.esic-cumprimento :as wire]))

(deftest esic-cumprimento-deriva-percentual
  (let [out (adapters/esic-cumprimento->wire {:total-encerrados 49 :cumpridos-no-prazo 47})]
    (is (m/validate wire/EsicCumprimentoOut out))
    (is (= 96 (:percentual out)))))

(deftest esic-cumprimento-zero-encerrados-percentual-nil
  (let [out (adapters/esic-cumprimento->wire {:total-encerrados 0 :cumpridos-no-prazo 0})]
    (is (m/validate wire/EsicCumprimentoOut out))
    (is (nil? (:percentual out)))))
