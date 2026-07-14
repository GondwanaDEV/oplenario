(ns oplenario.cadastros.vereador-adapters-out-test
  "UNIT (puro, sem DB) — o gate adapters/out de vereador (Onda D Slice 3, Task 3). Prova a forma
  (so' os campos de VereadorLinhaOut/VereadorFichaOut, uuid/LocalDate como string), a derivacao de
  cargo-mesa a partir de `comissoes` (nunca do mandato), e a validacao de contrato."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [oplenario.cadastros.adapters.out.vereador :as adapters]
            [oplenario.cadastros.wire.out.vereador :as wire])
  (:import (java.time LocalDate)))

(deftest ficha->wire-projeta-e-valida
  (let [id (random-uuid)
        ficha {:vereador {:id id :nome "Ana" :nome-parlamentar nil}
               :mandato {:partido "PT" :estado "vigente" :natureza "titular"
                         :vigencia-inicio (LocalDate/parse "2025-01-01")}
               :legislatura {:numero 19 :ano-inicio 2025 :ano-fim 2028}
               :comissoes [{:nome "Mesa Diretora" :tipo "mesa" :cargo "presidente"}
                           {:nome "Comissao de Justica" :tipo "permanente" :cargo nil}]}
        out (adapters/ficha->wire ficha)]
    (is (m/validate wire/VereadorFichaOut out) "a projecao satisfaz VereadorFichaOut")
    (testing "ids viram string"
      (is (string? (:id out)))
      (is (= (str id) (:id out))))
    (testing "cargo-mesa vem da comissao tipo=mesa, nao do mandato"
      (is (= "presidente" (get-in out [:mandato :cargo-mesa]))))
    (testing "legislatura achata para dentro do mandato"
      (is (= 19 (get-in out [:mandato :legislatura-numero])))
      (is (= 2025 (get-in out [:mandato :legislatura-ano-inicio])))
      (is (= 2028 (get-in out [:mandato :legislatura-ano-fim]))))
    (testing "vigencia-inicio (LocalDate) vira ISO string em :posse"
      (is (= "2025-01-01" (get-in out [:mandato :posse]))))
    (testing "as duas comissoes projetam"
      (is (= 2 (count (:comissoes out)))))))

(deftest ficha->wire-sem-mandato-fica-nil
  (let [ficha {:vereador {:id (random-uuid) :nome "Beto" :nome-parlamentar "Beto Silva"}
               :mandato nil :legislatura nil :comissoes []}
        out (adapters/ficha->wire ficha)]
    (is (m/validate wire/VereadorFichaOut out))
    (is (nil? (:mandato out)))
    (is (= [] (:comissoes out)))))

(deftest lista->wire-projeta-linha-com-partido-e-estado-nulos
  (let [row {:id (random-uuid) :nome "Carla" :nome-parlamentar nil
             :partido nil :estado-mandato nil :cargo-mesa nil}
        out (adapters/lista->wire [row])]
    (is (m/validate wire/VereadorLinhaOut (first out)))
    (is (nil? (:partido (first out))))
    (is (nil? (:estado-mandato (first out))))))

(deftest lista->wire-vazia
  (is (= [] (adapters/lista->wire []))))

(deftest ficha->wire-lanca-quando-nome-ausente
  (is (thrown? clojure.lang.ExceptionInfo
               (adapters/ficha->wire {:vereador {:id (random-uuid) :nome nil}
                                       :mandato nil :legislatura nil :comissoes []}))))
