(ns oplenario.motor.registro-fatos-esta-presente-em-test
  "C3: prova que `esta_presente_em` (sessoes/relacoes/presenca) casa a assinatura do catalogo — a mesma
  rede de costura que já protege presentes_plenario/remoto (§1, registro_fatos.clj)."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.motor.components.registro-fatos :as rf]
            [oplenario.sessoes.relacoes.presenca :as rel-sessoes]))

(deftest costura-esta-presente-em-ok
  (let [r (rf/verificar-costura rel-sessoes/relacoes)]
    (is (:ok r) (str "esperava costura ok, erros: " (:erros r)))))
