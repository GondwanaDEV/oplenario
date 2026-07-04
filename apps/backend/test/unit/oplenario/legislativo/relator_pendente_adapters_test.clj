(ns oplenario.legislativo.relator-pendente-adapters-test
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [oplenario.legislativo.adapters.out.relator-pendente :as adapters]
            [oplenario.legislativo.wire.out.relator-pendente :as wire]))

(deftest relatores-pendentes-projeta-e-valida
  (let [linhas [{:id (random-uuid) :proposicao-id (random-uuid) :tipo "pl" :ano 2026 :sequencial 51
                 :urn-lex "urn:lex:..." :ementa "Arborização viária"
                 :criado-em (java.time.Instant/parse "2026-07-01T12:00:00Z")}]
        out (adapters/relatores-pendentes->wire linhas)]
    (is (m/validate wire/RelatoresPendentesOut out))
    (is (= 1 (count (:itens out))))
    (is (string? (:proposicao-id (first (:itens out)))))))

(deftest relatores-pendentes-vazio
  (is (= {:itens []} (adapters/relatores-pendentes->wire []))))
