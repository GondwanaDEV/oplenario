(ns oplenario.transparencia.votacao-adapters-test
  "UNIT (puro, sem PG) — o gate de saida das votacoes publicas e a coercao de `?pagina=`. O que o portal NUNCA devolve:
  voto por vereador numa votacao que nao e' nominal (defesa em profundidade: mesmo se a fonte trouxer `:votos`)."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.transparencia.adapters.in.portal :as adapters-in]
            [oplenario.transparencia.adapters.out.votacao :as out]))

(def v1 (random-uuid))
(def v2 (random-uuid))

(defn- votacao [modalidade & [extra]]
  (merge {:id (random-uuid) :encerrada-em (java.time.Instant/parse "2026-09-10T15:00:00Z")
          :sessao {:sessao-id (random-uuid) :tipo-sessao "ordinaria" :numero-sequencial 12 :data nil}
          :objeto-tipo "proposicao" :modalidade modalidade :quorum-tipo "maioria_simples" :resultado "aprovada"
          :total-sim 2 :total-nao 1 :total-abstencao 0 :base-membros 3
          :votos [{:vereador-id v1 :voto "sim"} {:vereador-id v2 :voto "nao"}]}
         extra))

(deftest so-a-votacao-nominal-entrega-voto-por-vereador
  (testing "nominal: os votos saem, pelo nome, em ordem alfabetica"
    (let [r (out/detalhe->wire (votacao "nominal") {v1 "Zeca" v2 "Ana"})]
      (is (= [["Ana" "nao"] ["Zeca" "sim"]] (mapv (juxt :vereador :voto) (:votos r))))))
  (testing "secreta e simbolica: mesmo que a fonte traga votos, o wire sai sem nenhum"
    (doseq [m ["secreta" "simbolica"]]
      (is (= [] (:votos (out/detalhe->wire (votacao m) {v1 "Zeca" v2 "Ana"}))) m)))
  (testing "vereador sem nome conhecido nunca vira id cru na tela"
    (is (= ["Vereador sem nome publicado"] (distinct (map :vereador (:votos (out/detalhe->wire (votacao "nominal") {})))))))
  (testing "simbolica sem contagem: sem placar"
    (is (nil? (:placar (out/detalhe->wire (votacao "simbolica" {:total-sim nil :total-nao nil :total-abstencao nil
                                                                 :base-membros nil}) {}))))))

(deftest lista-leva-o-total-e-a-pagina
  (let [r (out/lista->wire {:votacoes [(votacao "nominal")] :total 41 :pagina 2 :por-pagina 20})]
    (is (= [41 2 20 1] [(:total r) (:pagina r) (:por-pagina r) (count (:votacoes r))]))
    (is (not (contains? (first (:votacoes r)) :votos)) "o item da lista nao carrega voto individual")))

(deftest query-pagina
  (is (= 1 (adapters-in/query-pagina nil)) "ausente = primeira pagina")
  (is (= 3 (adapters-in/query-pagina "3")))
  (doseq [ruim ["0" "-1" "abc" ["1" "2"] "99999999999999"]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"" (adapters-in/query-pagina ruim)) (str ruim))))
