(ns oplenario.legislativo.votacao-ia-logic-test
  "UNIT (puro): a votacao encerrada como o contexto da IA a le (A.6). O objeto em palavras, a aritmetica do quorum do
  core e — o que mais importa — a ALLOWLIST: nenhum campo de voto por vereador sobrevive, mesmo que a linha o traga."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.legislativo.logic.votacao-ia :as logic]))

(def linha
  {:id #uuid "aaaaaaaa-0000-0000-0000-000000000001" :objeto-tipo "proposicao" :modalidade "nominal"
   :quorum-tipo "maioria_simples" :base-membros 13 :resultado "aprovada"
   :total-sim 9 :total-nao 2 :total-abstencao 1 :encerrada-em :instante
   :materia-tipo "projeto_lei" :materia-ano 2026 :materia-sequencial 8})

(deftest objeto-em-palavras-so-resolve-o-que-tem-numero
  (let [materia {:tipo "projeto_lei" :ano 2026 :sequencial 8}]
    (is (= "PL 008/2026" (logic/objeto-em-palavras "proposicao" materia)))
    (is (= "redação final do PL 008/2026" (logic/objeto-em-palavras "redacao_final" materia)))
    (is (= "uma emenda" (logic/objeto-em-palavras "emenda" materia)) "outra tabela: vai pelo tipo")
    (is (= "um parecer" (logic/objeto-em-palavras "parecer" nil)))
    (is (= "um requerimento" (logic/objeto-em-palavras "requerimento" nil)))))

(deftest objeto-sem-materia-nao-inventa-titulo
  (is (= "uma proposição" (logic/objeto-em-palavras "proposicao" {:tipo nil :ano nil :sequencial nil})))
  (is (= "a redação final de uma proposição" (logic/objeto-em-palavras "redacao_final" nil))))

(deftest objeto-desconhecido-falha-alto
  (is (thrown? clojure.lang.ExceptionInfo (logic/objeto-em-palavras "outra-coisa" nil))))

(deftest votos-necessarios-e-a-aritmetica-do-core
  (is (nil? (logic/votos-necessarios "maioria_simples" 13)))
  (is (= 7 (logic/votos-necessarios "maioria_absoluta" 13)))
  (is (= 9 (logic/votos-necessarios "maioria_qualificada_2_3" 13)))
  (is (= 8 (logic/votos-necessarios "maioria_qualificada_3_5" 13)))
  (is (nil? (logic/votos-necessarios "maioria_qualificada_2_3" nil)) "sem base gravada nao inventa numero"))

(deftest para-contexto-e-uma-allowlist
  (let [c (logic/para-contexto linha)]
    (is (= #{:id :objeto :modalidade :quorum-tipo :votos-necessarios :base-membros :resultado
             :total-sim :total-nao :total-abstencao :encerrada-em}
           (set (keys c))))
    (is (= {:objeto "PL 008/2026" :modalidade "nominal" :resultado "aprovada" :total-sim 9 :total-nao 2
            :total-abstencao 1 :votos-necessarios nil}
           (select-keys c [:objeto :modalidade :resultado :total-sim :total-nao :total-abstencao :votos-necessarios])))))

(deftest voto-por-vereador-nao-sobrevive-nem-se-a-linha-o-trouxer
  (testing "uma linha que (por bug) carregue o voto individual"
    (let [suja (assoc linha :votos [{:vereador-id #uuid "bbbbbbbb-0000-0000-0000-000000000002" :voto "sim"}]
                      :vereador-id #uuid "bbbbbbbb-0000-0000-0000-000000000002" :voto "sim")
          c (logic/para-contexto suja)]
      (is (not (contains? c :votos)))
      (is (not (contains? c :vereador-id)))
      (is (not (contains? c :voto)))
      (is (not (re-find #"bbbbbbbb" (pr-str c)))))))
