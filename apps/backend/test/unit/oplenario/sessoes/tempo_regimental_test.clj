(ns oplenario.sessoes.tempo-regimental-test
  "UNIT (puro): mig 0081 — qual tempo regimental vale para uma fala. Das linhas candidatas (a da fase e a
  generica, fase nil), a ESPECIFICA vence; sem nenhuma, nil = a fala corre sem limite."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.sessoes.logic :as logic]))

(deftest especifica-vence-generica
  (is (= 600 (logic/escolher-tempo-regimental [{:fase nil :segundos 300} {:fase "ordem_do_dia" :segundos 600}])))
  (is (= 600 (logic/escolher-tempo-regimental [{:fase "ordem_do_dia" :segundos 600} {:fase nil :segundos 300}]))
      "a ordem das linhas vindas do banco nao decide nada"))

(deftest so-generica-vale
  (is (= 300 (logic/escolher-tempo-regimental [{:fase nil :segundos 300}]))))

(deftest sem-linha-sem-limite
  (is (nil? (logic/escolher-tempo-regimental [])))
  (is (nil? (logic/escolher-tempo-regimental nil))))

;; ---------- a tabela inteira, como a secretaria a salva (tela "Tempos da tribuna") ----------

(defn- item [fase tipo seg] {:fase fase :tipo-fala tipo :segundos seg})

(deftest tabela-valida-passa
  (is (nil? (logic/validar-tempos-regimentais! [])) "tabela vazia = a Casa volta a nao ter limite")
  (is (nil? (logic/validar-tempos-regimentais!
             [(item nil "principal" 180) (item "ordem_do_dia" "principal" 600) (item nil "aparte" 60)]))))

(deftest par-repetido-e-recusado
  (let [e (try (logic/validar-tempos-regimentais! [(item nil "aparte" 60) (item nil "aparte" 90)])
               nil (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e) "duas linhas para o mesmo (fase, tipo) — qual valeria? recusa")
    (is (= :validacao/invalido (:tipo (ex-data e))) "vira 400 na borda, nao 500")))

(deftest limites-dos-segundos
  (is (thrown? clojure.lang.ExceptionInfo (logic/validar-tempos-regimentais! [(item nil "aparte" 0)])))
  (is (thrown? clojure.lang.ExceptionInfo
               (logic/validar-tempos-regimentais! [(item nil "aparte" (inc logic/tempo-regimental-maximo-segundos))]))
      "teto contra digitacao errada (ex.: 3000 min em vez de 30)")
  (is (nil? (logic/validar-tempos-regimentais! [(item nil "aparte" logic/tempo-regimental-maximo-segundos)]))))

(deftest vocabulario-fechado
  (is (thrown? clojure.lang.ExceptionInfo (logic/validar-tempos-regimentais! [(item nil "cochicho" 60)])))
  (is (thrown? clojure.lang.ExceptionInfo (logic/validar-tempos-regimentais! [(item "recreio" "aparte" 60)]))))
