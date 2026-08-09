(ns oplenario.sessoes.presenca-lote-gate-test
  "UNIT (puro) — o desempate de VEREADOR REPETIDO dentro do MESMO lote de presenca (Etapa 2c da chamada,
  §22.6 eixo C). A chamada de uma camara e' UM ato de dezenas de nomes gravado numa unica transacao
  (`POST /sessoes/:id/presenca/lote`); duas linhas do MESMO lote apontando para o MESMO vereador sao uma
  AMBIGUIDADE que a ordem de uma lista JSON nao decide sozinha — RECUSA-SE o lote inteiro (400), nunca se
  escolhe 'a ultima vence' ou 'a primeira vence' em silencio."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.sessoes.logic :as logic]))

(defn- registro [vid] {:vereador-id vid})

(deftest lote-sem-repeticao-nao-acusa-duplicata
  (testing "N vereadores distintos -> nil (nada a recusar)"
    (let [a (random-uuid) b (random-uuid) c (random-uuid)]
      (is (nil? (logic/vereador-duplicado-no-lote [(registro a) (registro b) (registro c)]))))))

(deftest lote-vazio-nao-acusa-duplicata
  (is (nil? (logic/vereador-duplicado-no-lote []))))

(deftest lote-com-um-so-registro-nao-acusa-duplicata
  (is (nil? (logic/vereador-duplicado-no-lote [(registro (random-uuid))]))))

(deftest lote-com-vereador-repetido-e-acusado
  (testing "o MESMO vereador-id aparecendo duas vezes -> o id repetido (nao nil)"
    (let [a (random-uuid) b (random-uuid)]
      (is (= a (logic/vereador-duplicado-no-lote [(registro a) (registro b) (registro a)]))
          "acusa o id que se repetiu, para a mensagem de erro poder nomea-lo"))))

(deftest lote-com-repeticao-adjacente-e-acusado
  (let [a (random-uuid)]
    (is (= a (logic/vereador-duplicado-no-lote [(registro a) (registro a)])))))

(deftest lote-com-repeticao-tripla-acusa-na-segunda-ocorrencia
  ;; nao precisa contar QUANTAS vezes repete, so' que repete — a primeira repeticao ja' basta p/ recusar.
  (let [a (random-uuid) b (random-uuid)]
    (is (= a (logic/vereador-duplicado-no-lote [(registro a) (registro b) (registro a) (registro a)])))))
