(ns oplenario.motor.politica-test
  "F2.4 — `policy.check` no MESMO avaliador (disciplina 5): uma política é EXPRESSÃO DSL compilada em
  predicado `(fn [ator recurso] → bool)` e rodada por `kernel/autorizacao/check!`. Prova com a relação
  pura `é_o_próprio` (registrada): permite quando o ator é o próprio autor do recurso, NEGA quando não;
  política que LANÇA (fato sem fn) também NEGA (quem não decide, nega — nunca fica indeterminada)."
  (:require [clojure.test :refer [deftest is]]
            [com.stuartsierra.component :as component]
            [oplenario.identidade.relacoes.identidade :as rel-id]
            [oplenario.kernel.autorizacao :as autz]
            [oplenario.motor.api :as motor]
            [oplenario.motor.components.registro-fatos :as rf])
  (:import (java.time LocalDate)))

(defn- nega? [f]
  (try (f) false
       (catch clojure.lang.ExceptionInfo e (autz/negado? e))))

(deftest policy-dsl-por-relacao-dinamica
  (let [registro (component/start (rf/registro-fatos rel-id/relacoes))
        ;; política do módulo dono: editar o recurso exige ser o próprio autor (é_o_próprio é PURA → tx nil)
        politica (motor/politica-dsl {:registro registro :tx nil
                                      :expr "é_o_próprio(recurso.autor, ator.identidade)"
                                      :agora (LocalDate/of 2026 6 19)})
        eu (random-uuid)
        ator {:identidade eu :ente-id (random-uuid)}]
    (is (true? (autz/check! ator :editar {:autor eu} politica)) "ator é o próprio autor → permite")
    (is (nega? #(autz/check! ator :editar {:autor (random-uuid)} politica)) "ator não é o autor → NEGA")
    (component/stop registro)))

(deftest policy-dsl-fato-sem-fn-nega
  ;; política referencia um fato cuja fn NÃO está registrada neste registry → resolver lança → check! NEGA
  (let [registro (component/start (rf/registro-fatos rel-id/relacoes))
        politica (motor/politica-dsl {:registro registro :tx nil
                                      :expr "tem_mandato_vigente(ator.identidade, hoje())"
                                      :agora (LocalDate/of 2026 6 19)})
        ator {:identidade (random-uuid) :ente-id (random-uuid)}]
    (is (nega? #(autz/check! ator :votar {} politica)) "fato sem fn (mandato não-registrado aqui) → NEGA (fail-closed)")
    (component/stop registro)))
