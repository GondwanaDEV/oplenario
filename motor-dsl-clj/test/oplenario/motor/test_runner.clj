(ns oplenario.motor.test-runner
  "Runner ergonômico (espelha `python3 test_motor.py`): roda a suíte e sai != 0 se falhar.
   Uso: clojure -M:test"
  (:require [clojure.test :as t]
            [oplenario.motor.motor-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests 'oplenario.motor.motor-test)
        ruim (+ (or fail 0) (or error 0))]
    (println (if (zero? ruim) "RESULTADO: TODOS OS TESTES PASSARAM ✅"
                 (str "RESULTADO: " ruim " FALHA(S)")))
    (shutdown-agents)
    (System/exit (if (zero? ruim) 0 1))))
