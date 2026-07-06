(ns oplenario.legislativo.wire-out-proposicao-test
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [oplenario.legislativo.wire.out.proposicao :as wire]))

(def ^:private minima
  {:id "u" :tipo "projeto_lei" :ano 2026 :sequencial 1 :urn-lex "urn:x" :ementa "X" :estado "protocolada"
   :lock-version 0 :atualizado-em "2026-01-01T00:00:00Z"})

(deftest detalhe-minimo-valido
  (is (m/validate wire/ProposicaoDetalheOut minima)))

(deftest detalhe-com-texto-valido
  (is (m/validate wire/ProposicaoDetalheOut (assoc minima :texto "## Art. 1o"))))

(deftest detalhe-sem-lock-version-invalido
  (is (not (m/validate wire/ProposicaoDetalheOut (dissoc minima :lock-version)))))
