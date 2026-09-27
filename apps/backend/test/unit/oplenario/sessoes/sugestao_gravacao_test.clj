(ns oplenario.sessoes.sugestao-gravacao-test
  "UNIT (puro): Faixa A / A.2 — qual sessao sugerir para uma gravacao recebida sem vinculo, pelo horario."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.sessoes.logic :as logic])
  (:import (java.time Instant)))

(defn- t [s] (Instant/parse s))
(defn- sessao [id estado & {:as m}] (merge {:id id :estado estado} m))

(def terca (sessao "a" "encerrada" :aberta-em (t "2026-09-22T18:00:00Z") :encerrada-em (t "2026-09-22T21:00:00Z")))
(def quinta (sessao "b" "encerrada" :aberta-em (t "2026-09-24T18:00:00Z") :encerrada-em (t "2026-09-24T20:00:00Z")))

(deftest casa-a-sessao-cuja-janela-contem-o-inicio-da-gravacao
  (is (= "a" (:id (logic/sugerir-sessao-da-gravacao (t "2026-09-22T17:40:00Z") [terca quinta])))
      "o OBS comecou 20 min antes da abertura: dentro da folga")
  (is (= "b" (:id (logic/sugerir-sessao-da-gravacao (t "2026-09-24T19:00:00Z") [terca quinta])))))

(deftest fora-de-qualquer-janela-nao-sugere
  (is (nil? (logic/sugerir-sessao-da-gravacao (t "2026-09-23T12:00:00Z") [terca quinta])))
  (is (nil? (logic/sugerir-sessao-da-gravacao (t "2026-09-22T18:00:00Z") []))))

(deftest nao-realizada-nunca-e-sugerida
  (let [nr (sessao "c" "nao_realizada" :agendada-para (t "2026-09-22T18:00:00Z"))]
    (is (nil? (logic/sugerir-sessao-da-gravacao (t "2026-09-22T18:05:00Z") [nr])))))

(deftest sem-encerramento-usa-janela-presumida-e-agendada-para
  (let [agendada (sessao "d" "agendada" :agendada-para (t "2026-09-29T18:00:00Z"))]
    (is (= "d" (:id (logic/sugerir-sessao-da-gravacao (t "2026-09-30T01:00:00Z") [agendada])))
        "sem encerrada-em: janela de 6h + 2h de folga a partir do agendamento")
    (is (nil? (logic/sugerir-sessao-da-gravacao (t "2026-09-30T03:00:00Z") [agendada])))))

(deftest duas-candidatas-vence-o-inicio-mais-proximo
  (let [manha (sessao "m" "encerrada" :aberta-em (t "2026-09-22T09:00:00Z") :encerrada-em (t "2026-09-22T17:30:00Z"))]
    (is (= "a" (:id (logic/sugerir-sessao-da-gravacao (t "2026-09-22T17:50:00Z") [manha terca])))
        "a extraordinaria da manha ainda cobre 17:50 pela folga, mas a de 18:00 comeca mais perto")))

(deftest aceita-os-tipos-de-instante-que-o-banco-devolve
  (let [s (sessao "e" "encerrada" :aberta-em (java.sql.Timestamp/from (t "2026-09-22T18:00:00Z")))]
    (is (= "e" (:id (logic/sugerir-sessao-da-gravacao (java.time.OffsetDateTime/parse "2026-09-22T18:30:00Z") [s]))))))
