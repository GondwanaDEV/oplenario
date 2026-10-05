(ns oplenario.legislativo.regra-votacao-logic-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [oplenario.legislativo.logic.regra-votacao :as regra]))

(deftest a-regra-sai-da-materia
  (testing "o PDL das contas do Prefeito"
    (is (= "contas_prefeito" (regra/chave-da-materia {:tipo "projeto_decreto_legislativo"} {:tipo "governo_prefeito"}))))
  (testing "as contas da Mesa nao votam pela regra (so' acompanhamento)"
    (is (nil? (regra/chave-da-materia {:tipo "projeto_decreto_legislativo"} {:tipo "gestao_camara"}))))
  (testing "a emenda a Lei Organica"
    (is (= "emenda_lom" (regra/chave-da-materia {:tipo "proposta_emenda_lom"} nil))))
  (testing "as outras especies (e a proposicao inexistente) votam com o quorum da Mesa"
    (doseq [t ["projeto_lei" "projeto_lei_complementar" "requerimento" "mocao" "indicacao"]]
      (is (nil? (regra/chave-da-materia {:tipo t} nil)) t))
    (is (nil? (regra/chave-da-materia nil nil)))))

(deftest a-recusa-em-palavras
  (is (= "A emenda à Lei Orgânica só é aprovada com 2/3 dos membros da Câmara (CF art. 29)."
         (regra/recusa-da-guarda "emenda_lom" "CF art. 29")))
  (is (str/starts-with? (regra/recusa-da-guarda "contas_prefeito" "CF art. 31 §2") "O julgamento das contas do Prefeito"))
  (is (= "A regra de votação da emenda à Lei Orgânica não está configurada." (regra/regra-ausente "emenda_lom"))))
