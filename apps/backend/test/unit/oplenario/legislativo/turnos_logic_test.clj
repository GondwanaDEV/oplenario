(ns oplenario.legislativo.turnos-logic-test
  "UNIT (puro): a materia de dois turnos (a emenda a Lei Organica, CF art. 29). A conta sobre as votacoes encerradas
  da materia — qual turno cada uma foi, se a materia esta' aprovada, se a proxima votacao abre hoje — e a recusa em
  palavras que a Mesa le."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [oplenario.legislativo.logic.regra-votacao :as regra-votacao]
            [oplenario.legislativo.logic.turnos :as turnos])
  (:import (java.time Instant LocalDate)))

(def ^:private emenda {:chave "emenda_lom" :referencia "CF art. 29" :turnos 2 :intersticio-dias 10})

(defn- v
  "Votacao encerrada de turno: aberta e encerrada no instante (UTC) dado."
  [resultado aberta encerrada & {:as extra}]
  (merge {:id (random-uuid) :objeto-tipo "proposicao" :resultado resultado :texto-versao-id (random-uuid)
          :aberta-em (Instant/parse aberta) :encerrada-em (Instant/parse encerrada)}
         extra))

;; 1o turno encerrado em 05/10/2026 as 21h de Fortaleza (00h UTC do dia 06): o dia civil da Casa e' o 05.
(def ^:private turno-1 (v "aprovada" "2026-10-05T22:00:00Z" "2026-10-06T00:00:00Z"))

(deftest o-turno-de-cada-votacao
  (let [rej (v "rejeitada" "2026-10-20T13:00:00Z" "2026-10-20T14:00:00Z")
        rf (v "aprovada" "2026-10-21T13:00:00Z" "2026-10-21T14:00:00Z" :objeto-tipo "redacao_final")]
    (is (= [1 2 nil] (mapv :turno (turnos/com-turno 2 [turno-1 rej rf])))
        "a rejeicao e' do turno que se votava; a redacao final nao e' turno")
    (is (= [1 1] (mapv :turno (turnos/com-turno 2 [(assoc turno-1 :resultado "rejeitada") rej])))
        "rejeitada no 1o turno, a seguinte ainda e' do 1o")
    (is (= [1 2 2] (mapv :turno (turnos/com-turno 2 [turno-1 turno-1 turno-1])))
        "o turno nunca passa do numero de turnos da regra")))

(deftest aprovada-so-com-os-dois-turnos-e-o-intersticio
  (testing "um turno so' nao aprova"
    (is (nil? (turnos/aprovacao emenda [turno-1]))))
  (testing "o 2o turno aberto no 10o dia civil depois do encerramento do 1o: aprova, e o texto e' o do 2o"
    (let [t2 (v "aprovada" "2026-10-15T12:00:00Z" "2026-10-15T13:00:00Z")]
      (is (= (:id t2) (:id (turnos/aprovacao emenda [turno-1 t2]))))))
  (testing "aberto no 9o dia (pelo dia civil da Casa, nao pelo UTC): nao aprova"
    ;; 15/10 as 01h UTC ainda e' 14/10 em Fortaleza
    (is (nil? (turnos/aprovacao emenda [turno-1 (v "aprovada" "2026-10-15T01:00:00Z" "2026-10-15T02:00:00Z")]))))
  (testing "uma rejeicao em qualquer turno impede"
    (let [t2 (v "aprovada" "2026-10-16T12:00:00Z" "2026-10-16T13:00:00Z")]
      (is (nil? (turnos/aprovacao emenda [turno-1 (v "rejeitada" "2026-10-15T12:00:00Z" "2026-10-15T13:00:00Z") t2])))
      (is (nil? (turnos/aprovacao emenda [(assoc turno-1 :resultado "rejeitada") turno-1 t2])))))
  (testing "a redacao final aprovada DEPOIS dos dois turnos e' o texto que vale; sozinha, nao aprova"
    (let [t2 (v "aprovada" "2026-10-15T12:00:00Z" "2026-10-15T13:00:00Z")
          rf (v "aprovada" "2026-10-16T12:00:00Z" "2026-10-16T13:00:00Z" :objeto-tipo "redacao_final")]
      (is (= (:id rf) (:id (turnos/aprovacao emenda [turno-1 t2 rf]))))
      (is (nil? (turnos/aprovacao emenda [turno-1 rf])) "redacao final nao conta como 2o turno")))
  (testing "abertura sem instante (dado anterior ao carimbo): falha fechada"
    (is (nil? (turnos/aprovacao emenda [turno-1 (v "aprovada" "2026-10-16T12:00:00Z" "2026-10-16T13:00:00Z"
                                                   :aberta-em nil)])))))

(deftest o-que-a-proxima-votacao-pode-abrir
  (is (= {:turno 1} (turnos/para-abrir emenda [] (LocalDate/parse "2026-10-05"))))
  (is (= {:recusa :intersticio :turno 2 :anterior 1 :a-partir-de (LocalDate/parse "2026-10-15")}
         (turnos/para-abrir emenda [turno-1] (LocalDate/parse "2026-10-14"))))
  (is (= {:turno 2} (turnos/para-abrir emenda [turno-1] (LocalDate/parse "2026-10-15"))))
  (is (= {:recusa :concluida}
         (turnos/para-abrir emenda [turno-1 (v "aprovada" "2026-10-15T12:00:00Z" "2026-10-15T13:00:00Z")]
                            (LocalDate/parse "2026-11-01"))))
  (is (= {:recusa :rejeitada :turno 2}
         (turnos/para-abrir emenda [turno-1 (v "rejeitada" "2026-10-15T12:00:00Z" "2026-10-15T13:00:00Z")]
                            (LocalDate/parse "2026-11-01"))))
  (testing "a redacao final nao entra na conta dos turnos"
    (is (= {:turno 1} (turnos/para-abrir emenda [(assoc turno-1 :objeto-tipo "redacao_final")]
                                         (LocalDate/parse "2026-10-05"))))))

(deftest a-recusa-em-palavras
  (is (= (str "O 2º turno desta emenda à Lei Orgânica só pode ser votado a partir de 15/10/2026: "
              "são 10 dias depois do 1º turno (CF art. 29).")
         (regra-votacao/recusa-do-turno emenda {:recusa :intersticio :turno 2 :anterior 1
                                                :a-partir-de (LocalDate/parse "2026-10-15")})))
  (is (= "Esta emenda à Lei Orgânica já foi aprovada nos dois turnos (CF art. 29)."
         (regra-votacao/recusa-do-turno emenda {:recusa :concluida})))
  (let [frase (regra-votacao/recusa-do-turno emenda {:recusa :rejeitada :turno 2})]
    (is (str/starts-with? frase "Esta emenda à Lei Orgânica foi rejeitada no 2º turno"))
    (is (not (str/includes? frase "CF art. 29")) "a CF nao fala da materia rejeitada: a frase nao a cita")))
