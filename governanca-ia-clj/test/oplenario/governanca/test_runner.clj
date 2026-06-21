(ns oplenario.governanca.test-runner
  "Runner ergonômico (espelha motor-dsl-clj). Uso: clojure -M:test"
  (:require [clojure.test :as t]
            [oplenario.governanca.governanca-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests 'oplenario.governanca.governanca-test)
        ruim (+ (or fail 0) (or error 0))]
    (println (if (zero? ruim) "GOVERNANCA: TODOS OS TESTES PASSARAM ✅" (str "GOVERNANCA: " ruim " FALHA(S)")))
    (shutdown-agents)
    (System/exit (if (zero? ruim) 0 1))))
