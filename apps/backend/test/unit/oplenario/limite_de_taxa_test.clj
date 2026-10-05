(ns oplenario.limite-de-taxa-test
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.limite-de-taxa :as lt]))

(deftest admite-ate-o-maximo-na-janela-e-recusa-o-resto
  (let [l (lt/novo {:maximo 3 :janela-ms 1000})]
    (is (every? :permitido? (map #(lt/tentar! l "1.2.3.4" %) [0 10 20])))
    (let [r (lt/tentar! l "1.2.3.4" 30)]
      (is (false? (:permitido? r)) "a 4a dentro da janela e' recusada")
      (is (= 970 (:espera-ms r)) "espera ate' a mais antiga sair da janela"))
    (testing "a recusa nao conta como tentativa: quando a mais antiga sai, abre uma vaga"
      (is (:permitido? (lt/tentar! l "1.2.3.4" 1001)))
      (is (false? (:permitido? (lt/tentar! l "1.2.3.4" 1002)))))))

(deftest chaves-sao-independentes
  (let [l (lt/novo {:maximo 1 :janela-ms 1000})]
    (is (:permitido? (lt/tentar! l "a" 0)))
    (is (:permitido? (lt/tentar! l "b" 0)) "o IP de um nao gasta a cota do outro")
    (is (false? (:permitido? (lt/tentar! l "a" 1))))))

(deftest a-memoria-nao-guarda-chave-velha
  (let [l (lt/novo {:maximo 5 :janela-ms 100 :max-chaves 2})]
    (lt/tentar! l "a" 0)
    (lt/tentar! l "b" 0)
    (lt/tentar! l "c" 500)
    (is (= #{"c"} (set (keys @(:estado l)))) "passou do teto de chaves: as que so' tem tentativa vencida saem")))
