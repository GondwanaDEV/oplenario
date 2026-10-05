(ns oplenario.suporte-cpf-test
  "O gerador de CPF dos testes: todo CPF passa no validador do sistema e nenhum se repete na corrida — a colisao
  em `identidade_cpf_key` era o que derrubava a suite de vez em quando (PR #183)."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.kernel.cpf :as kcpf]
            [oplenario.suporte-cpf :refer [cpf-valido]]))

(deftest dez-mil-cpfs-validos-e-distintos
  (let [cpfs (repeatedly 10000 cpf-valido)]
    (is (every? kcpf/valido? cpfs))
    (is (= 10000 (count (set cpfs))))))
