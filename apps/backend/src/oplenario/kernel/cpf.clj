(ns oplenario.kernel.cpf
  "O CPF valido pelo digito verificador — regra transversal (a identidade ancora nele; o console do operador o
  recebe ao convidar o 1o administrador de uma Casa, ADR-0016). Pura.")

(defn- dv [digitos pesos]
  ;; digito verificador de CPF: soma(digito*peso) mod 11; <2 -> 0, senao 11-resto.
  (let [r (mod (reduce + (map * digitos pesos)) 11)]
    (if (< r 2) 0 (- 11 r))))

(defn valido?
  "11 digitos, nao todos iguais, os dois verificadores conferem."
  [cpf]
  (boolean
   (when (and (string? cpf) (re-matches #"\d{11}" cpf) (not (apply = cpf)))
     (let [d (mapv #(Character/digit ^char % 10) cpf)]
       (and (= (nth d 9) (dv (subvec d 0 9) (range 10 1 -1)))
            (= (nth d 10) (dv (subvec d 0 10) (range 11 1 -1))))))))
