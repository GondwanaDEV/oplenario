(ns oplenario.legislativo.wire-in-proposicao-test
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [oplenario.legislativo.wire.in.proposicao :as wire]))

(deftest criar-proposicao-minima-valida
  (is (m/validate wire/CriarProposicao {:tipo "projeto_lei" :ano 2026 :ementa "X"})))

(deftest criar-proposicao-tipo-desconhecido-invalido
  (is (not (m/validate wire/CriarProposicao {:tipo "decreto_alienigena" :ano 2026 :ementa "X"}))))

(deftest criar-proposicao-campo-extra-invalido
  (is (not (m/validate wire/CriarProposicao {:tipo "projeto_lei" :ano 2026 :ementa "X" :campo-fantasma 1}))))

(deftest editar-proposicao-exige-lock-version
  (is (not (m/validate wire/EditarProposicao {:ementa "X"})))
  (is (m/validate wire/EditarProposicao {:lock-version 0})))
