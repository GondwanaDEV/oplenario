(ns oplenario.admin-sistema.somar-resumos-test
  (:require [clojure.test :refer [deftest is]]
            [oplenario.admin-sistema.logic :as c]))

(deftest sem-anterior-e-o-atual
  (is (= {:linhas-total 1} (c/somar-resumos nil {:linhas-total 1}))))

(deftest soma-contagens-e-guarda-o-estado-da-ultima
  (let [r (c/somar-resumos {"tabelas" {"a.b" 3} "linhas-total" 3 "objetos" 2 "exportacoes-apagadas" 1
                            "objetos-fora-da-convencao" ["x"] "exportacao" {"id" "e1"} "completo?" false}
                           {:tabelas {"a.b" 0 "c.d" 1} :linhas-total 1 :objetos 0 :exportacoes-apagadas 0
                            :objetos-fora-da-convencao ["x" "y"] :completo? true :realm-apagado? true})]
    (is (= {"a.b" 3 "c.d" 1} (:tabelas r)))
    (is (= 4 (:linhas-total r)))
    (is (= 2 (:objetos r)))
    (is (= 1 (:exportacoes-apagadas r)))
    (is (= ["x" "y"] (:objetos-fora-da-convencao r)))
    (is (= {"id" "e1"} (:exportacao r)) "a exportacao entregue e' a da primeira execucao que a trouxe")
    (is (true? (:completo? r)))
    (is (true? (:realm-apagado? r)))))
