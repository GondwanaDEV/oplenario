(ns oplenario.legislativo.contas-logic-test
  "ADR-0021 Parte B — a logica pura do julgamento das contas: o estado derivado, o motivo da pauta, o 2/3 dos membros e a
  frase do resultado."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.legislativo.logic :as logic]
            [oplenario.legislativo.logic.contas :as contas])
  (:import (java.time Instant LocalDate)))

(def ^:private d #(LocalDate/parse %))

(def ^:private governo
  {:tipo "governo_prefeito" :exercicio 2024 :recebida-em (d "2026-08-01") :parecer-previo "favoravel"})

(deftest estado-derivado
  (let [hoje (d "2026-10-10")]
    (testing "a Mesa so' acompanha"
      (is (= "acompanhamento" (contas/estado {:tipo "gestao_camara"} hoje))))
    (testing "sem notificacao, aguarda"
      (is (= "aguardando_notificacao" (contas/estado governo hoje))))
    (let [notificada (assoc governo :notificado-em (d "2026-10-05") :prazo-defesa-ate (d "2026-10-20"))]
      (testing "notificada, dentro do prazo: prazo de defesa (o ultimo dia ainda e' do responsavel)"
        (is (= "prazo_de_defesa" (contas/estado notificada hoje)))
        (is (= "prazo_de_defesa" (contas/estado notificada (d "2026-10-20")))))
      (testing "prazo vencido OU defesa juntada: pronta para a pauta"
        (is (= "pronta_para_pauta" (contas/estado notificada (d "2026-10-21"))))
        (is (= "pronta_para_pauta" (contas/estado (assoc notificada :defesa-juntada-em (Instant/now)) hoje))))
      (testing "com resultado, julgada (vale sobre tudo)"
        (is (= "julgada" (contas/estado (assoc notificada :resultado "parecer_mantido") hoje)))))))

(deftest motivo-da-pauta-em-palavras
  (let [hoje (d "2026-10-10")
        notificada (assoc governo :notificado-em (d "2026-10-05") :prazo-defesa-ate (d "2026-10-20"))]
    (is (= "O responsável ainda não foi notificado." (contas/motivo-nao-pautavel governo hoje)))
    (is (= "O prazo de defesa do responsável vai até 20/10/2026." (contas/motivo-nao-pautavel notificada hoje)))
    (is (nil? (contas/motivo-nao-pautavel notificada (d "2026-10-21"))))
    (is (= "As contas deste exercício já foram julgadas."
           (contas/motivo-nao-pautavel (assoc notificada :resultado "parecer_rejeitado") hoje)))))

(deftest dois-tercos-dos-membros
  (testing "ceil(2N/3) com aritmetica inteira"
    (is (= 6 (contas/necessarios-para-rejeitar 9)))
    (is (= 9 (contas/necessarios-para-rejeitar 13)))
    (is (= 14 (contas/necessarios-para-rejeitar 21))))
  (testing "e' a MESMA aritmetica que decide a votacao"
    (doseq [n [9 13 21]]
      (let [k (contas/necessarios-para-rejeitar n)]
        (is (= "aprovada" (logic/resultado-votacao "maioria_qualificada_2_3" {:sim k :nao 0} n)))
        (is (= "rejeitada" (logic/resultado-votacao "maioria_qualificada_2_3" {:sim (dec k) :nao 0} n)))))))

(deftest resultado-da-votacao-sobre-o-parecer
  (is (= "parecer_rejeitado" (contas/resultado-da-votacao "aprovada")))
  (is (= "parecer_mantido" (contas/resultado-da-votacao "rejeitada"))))

(deftest frase-do-resultado
  (is (= "O parecer prevalece: 12 votos pela rejeição, eram precisos 14."
         (contas/frase-resultado "parecer_mantido" 12 14)))
  (is (= "O parecer foi rejeitado: 15 votos pela rejeição, eram precisos 14."
         (contas/frase-resultado "parecer_rejeitado" 15 14)))
  (is (= "O parecer prevalece: 1 voto pela rejeição, eram precisos 6."
         (contas/frase-resultado "parecer_mantido" 1 6)))
  (is (= "O parecer prevalece." (contas/frase-resultado "parecer_mantido" nil nil))
      "julgamento sem votacao registrada: so' a conclusao")
  (is (nil? (contas/frase-resultado nil 3 14))))

(deftest prazos-congelados-e-padroes
  (is (= (d "2026-09-30") (contas/prazo-julgamento-ate (d "2026-08-01") 60)))
  (is (= (d "2026-10-20") (contas/prazo-defesa-ate (d "2026-10-05") 15)))
  (is (= {:prazo-defesa-dias 15 :prazo-julgamento-dias 60 :padrao true} (contas/parametros-efetivos nil)))
  (is (= {:prazo-defesa-dias 10 :prazo-julgamento-dias 90 :padrao false}
         (contas/parametros-efetivos {:prazo-defesa-dias 10 :prazo-julgamento-dias 90}))))

(deftest ementa-e-chave
  (is (= "Dispõe sobre o julgamento das contas do Prefeito Municipal relativas ao exercício de 2024."
         (contas/ementa-do-pdl 2024)))
  (is (= "contas/e/p/d" (contas/chave-do-documento "e" "p" "d"))))
