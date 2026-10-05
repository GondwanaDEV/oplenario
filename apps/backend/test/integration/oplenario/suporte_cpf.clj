(ns oplenario.suporte-cpf
  "Suporte de teste: um CPF valido que NAO se repete na corrida.

  Cada arquivo de teste tinha a sua copia de um gerador que sorteava 9 digitos. Com ~1 bilhao de bases e
  centenas de identidades por corrida, duas acabavam iguais e a insercao estourava `identidade_cpf_key` (uma
  vez a cada ~8 mil corridas, PR #183). Aqui a base vem de um contador que comeca num ponto sorteado: dentro da
  mesma JVM nunca repete, e o ponto de partida sorteado mantem corridas seguidas num banco persistente longe
  umas das outras."
  (:require [oplenario.kernel.cpf :as kcpf]))

(def ^:private total-de-bases 1000000000)

(defonce ^:private proxima (atom (rand-int total-de-bases)))

(defn- verificador [ds]
  (let [r (mod (reduce + (map * ds (range (inc (count ds)) 1 -1))) 11)]
    (if (< r 2) 0 (- 11 r))))

(defn- com-verificadores [n]
  (let [base (mapv #(Character/digit ^char % 10) (format "%09d" n))
        d1 (verificador base)]
    (apply str (concat base [d1 (verificador (conj base d1))]))))

(defn cpf-valido
  "Um CPF de 11 digitos que passa em `oplenario.kernel.cpf/valido?` e ainda nao saiu nesta JVM."
  []
  (loop []
    (let [cpf (com-verificadores (swap! proxima #(mod (inc %) total-de-bases)))]
      ;; a base de digitos todos iguais (000000000, 111111111...) da um CPF que o sistema recusa: pula
      (if (kcpf/valido? cpf) cpf (recur)))))
