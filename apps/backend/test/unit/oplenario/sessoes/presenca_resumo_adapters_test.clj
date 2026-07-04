(ns oplenario.sessoes.presenca-resumo-adapters-test
  "UNIT (puro) — sessoes/adapters/out/presenca: resumo-presenca->wire."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [oplenario.sessoes.adapters.out.presenca :as adapters]
            [oplenario.sessoes.wire.out :as wire]))

(deftest resumo-presenca-valida-contrato
  (let [out (adapters/resumo-presenca->wire {:media-percentual 78 :sessoes-consideradas 10 :membros-da-casa 43})]
    (is (m/validate wire/PresencaResumoOut out))
    (is (= 78 (:media-percentual out)))))

(deftest resumo-presenca-aceita-media-nil
  (let [out (adapters/resumo-presenca->wire {:media-percentual nil :sessoes-consideradas 0 :membros-da-casa 43})]
    (is (m/validate wire/PresencaResumoOut out))
    (is (nil? (:media-percentual out)))))
