(ns oplenario.legislativo.meus-votos-logic-test
  "Logica pura dos votos do proprio vereador: a marca do que o portal faz com o voto (fail-closed no lado de avisar)."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.legislativo.logic.meus-votos :as logic]))

(def ^:private publica (random-uuid))
(def ^:private fechada (random-uuid))

(deftest portal-do-voto-segue-o-conjunto-de-sessoes-publicas
  (testing "sessao no conjunto: o portal publica"
    (is (= "publico" (logic/portal-do-voto-de publica #{publica}))))
  (testing "sessao fora do conjunto (secreta ou fechada ao publico): so' o vereador ve"
    (is (= "sessao-fechada" (logic/portal-do-voto-de fechada #{publica}))))
  (testing "votacao sem sessao: o portal nao publica"
    (is (= "sem-sessao" (logic/portal-do-voto-de nil #{publica})))))

(deftest sem-conjunto-nada-e-publico
  ;; seam fora, falha ou nil: o erro cai no lado de AVISAR o vereador, nunca no de prometer publicidade.
  (doseq [vazio [#{} nil []]]
    (is (= "sessao-fechada" (logic/portal-do-voto-de publica vazio)))))

(deftest marcar-voto-acrescenta-portal-e-anulada
  (let [v {:votacao-id (random-uuid) :voto "sim" :sessao-id publica :votacao-estado "encerrada"}]
    (is (= {:portal "publico" :anulada false} (select-keys (logic/marcar-voto #{publica} v) [:portal :anulada])))
    (is (= {:portal "sessao-fechada" :anulada true}
           (select-keys (logic/marcar-voto #{} (assoc v :votacao-estado "anulada")) [:portal :anulada])))
    (testing "o vocabulario do wire cobre toda marca possivel"
      (is (every? logic/portal-do-voto [(:portal (logic/marcar-voto #{publica} v))
                                        (:portal (logic/marcar-voto #{} v))
                                        (:portal (logic/marcar-voto #{} (assoc v :sessao-id nil)))])))))
