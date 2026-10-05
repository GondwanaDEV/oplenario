(ns oplenario.sessoes.rotulos-test
  "UNIT (puro) — os rotulos em palavras da sessao e do cargo na Mesa (`sessoes.logic.rotulos`), que a folha de
  presenca usa no lugar do prefixo do UUID e da chave crua do cargo."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.sessoes.logic.rotulos :as rotulos])
  (:import (java.time LocalDate)))

(deftest titulo-da-sessao-tem-tipo-numero-e-data
  (is (= "Sessão ordinária nº 3 de 01/10/2026"
         (rotulos/titulo-da-sessao {:tipo-sessao "ordinaria" :numero-sequencial 3} (LocalDate/of 2026 10 1))))
  (is (= "Audiência pública nº 1 de 05/10/2026"
         (rotulos/titulo-da-sessao {:tipo-sessao "audiencia_publica" :numero-sequencial 1} (LocalDate/of 2026 10 5))))
  (is (= "Sessão extraordinária nº 2"
         (rotulos/titulo-da-sessao {:tipo-sessao "extraordinaria" :numero-sequencial 2} nil))))

(deftest nome-da-sessao-minusculo-para-o-meio-da-frase
  (is (= "sessão ordinária nº 3" (rotulos/nome-da-sessao {:tipo-sessao "ordinaria" :numero-sequencial 3})))
  (is (= "audiência pública nº 1" (rotulos/nome-da-sessao {:tipo-sessao "audiencia_publica" :numero-sequencial 1}))))

(deftest sessao-de-e-o-texto-neutro
  (is (= "Sessão de 20/06/2026" (rotulos/sessao-de (LocalDate/of 2026 6 20))))
  (is (= "Sessão" (rotulos/sessao-de nil))))

(deftest cargo-na-mesa-em-palavras
  (is (= "Presidência" (rotulos/rotulo-do-cargo-na-mesa "presidente")))
  (is (= "Presidência" (rotulos/rotulo-do-cargo-na-mesa "Presidente")))
  (is (= "Vice-presidência" (rotulos/rotulo-do-cargo-na-mesa "vice")))
  (is (= "Vice-presidência" (rotulos/rotulo-do-cargo-na-mesa "vice_presidente")))
  (is (= "1ª Secretaria" (rotulos/rotulo-do-cargo-na-mesa "1_secretario")))
  (is (= "2ª Secretaria" (rotulos/rotulo-do-cargo-na-mesa "2_secretario")))
  (is (= "1ª Vice-presidência" (rotulos/rotulo-do-cargo-na-mesa "1_vice_presidente")))
  (is (= "Secretaria" (rotulos/rotulo-do-cargo-na-mesa "secretario")))
  (is (= "2ª Secretária da Mesa" (rotulos/rotulo-do-cargo-na-mesa "2ª Secretária da Mesa"))
      "texto ja' por extenso passa como esta'")
  (is (= "Corregedor geral" (rotulos/rotulo-do-cargo-na-mesa "corregedor_geral"))
      "chave desconhecida: `_` vira espaco, nunca a chave crua")
  (is (nil? (rotulos/rotulo-do-cargo-na-mesa nil)))
  (is (nil? (rotulos/rotulo-do-cargo-na-mesa "  "))))
