(ns oplenario.legislativo.nota-tecnica-test
  "UNIT: B.8 — a nota tecnica de conferencia sem banco: o texto que a secretaria aproveita (sem as marcas de citacao,
  igual ao `texto_limpo` do satelite), a decisao que a borda aceita e a projecao para a tela."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.legislativo.adapters.in.nota-tecnica :as in]
            [oplenario.legislativo.adapters.out.nota-tecnica :as out]
            [oplenario.legislativo.logic :as logic]))

(deftest texto-limpo-tira-as-marcas
  (is (= "O pedido cabe na competencia da Camara.\n\nVer o art. 45."
         (logic/texto-limpo (str "O pedido cabe na competencia da Camara [[norma:1#art25 | compete a Camara]].\n\n"
                                 "Ver o art. 45 [[norma:1#art45]] .")))
      "marca com e sem trecho; sem espaco antes da pontuacao; paragrafos preservados")
  (is (= "Sem marca." (logic/texto-limpo "  Sem marca.  ")))
  (is (= "" (logic/texto-limpo nil))))

(defn- tipo [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:tipo (ex-data e)))))

(deftest a-decisao-que-a-borda-aceita
  (is (= {:desfecho "descartada"} (in/decisao->dominio {"desfecho" "descartada" "texto" "ignorado"})))
  (is (= {:desfecho "aproveitada"} (in/decisao->dominio {"desfecho" "aproveitada" "texto" "   "}))
      "texto em branco = o do agente")
  (is (= {:desfecho "aproveitada" :texto "Editado."} (in/decisao->dominio {"desfecho" "aproveitada" "texto" " Editado. "})))
  (is (= :validacao/invalido (tipo #(in/decisao->dominio {"desfecho" "aprovada"}))))
  (is (= :validacao/invalido (tipo #(in/decisao->dominio {"desfecho" "aproveitada" "texto" (apply str (repeat 20001 "a"))}))))
  (testing "o filtro da fila"
    (is (= "pendente" (in/estado-da-fila {})))
    (is (nil? (in/estado-da-fila {:estado "todas"})))
    (is (= :validacao/invalido (tipo #(in/estado-da-fila {:estado "qualquer"}))))))

(deftest a-projecao-nao-leva-quem-decidiu
  (let [n {:id (random-uuid) :proposicao-id (random-uuid) :tipo "requerimento" :sequencial 3 :ano 2026
           :ementa "Informacoes sobre a praca" :estado "aproveitada" :incerteza "normal" :agente "conferencia-normativa"
           :execucao-id (random-uuid) :decidida-por (random-uuid)
           :texto "Cabe [[norma:1#art25 | compete a Camara]]." :texto-final "Cabe."
           :citacoes [{:fonte-id "norma:1#art25" :trecho "compete a Camara" :status "conferida" :rotulo "LOM, art. 25"}]
           :paragrafos-sem-fonte [] :motivos-incerteza [] :modelo-llm-id "fake:x"
           :criada-em (java.time.Instant/parse "2026-09-27T12:00:00Z") :decidida-em (java.sql.Timestamp. 0)}
        w (out/nota->wire n)]
    (is (= "Cabe." (:texto-limpo w)))
    (is (= "1970-01-01T00:00:00Z" (:decidida-em w)))
    (is (not-any? #{:decidida-por :execucao-id} (keys w)))
    (testing "feature 8.4: `execucao-ia` (id na IA, p/ o 'Reportar erro') sai como string so' quando ha'"
      (is (not (contains? w :execucao-ia)))
      (let [id (random-uuid)]
        (is (= (str id) (:execucao-ia (out/nota->wire (assoc n :execucao-ia id)))))
        (is (not (contains? (out/nota->wire (assoc n :execucao-ia nil)) :execucao-ia)))))
    (is (= [(str (:id n))] (mapv :id (:itens (out/notas->wire [n])))))
    (testing "ADR-0019 Eixo 5: a fila diz se a Casa tem juridico ativo (padrao: nao)"
      (is (false? (:casa-com-juridico (out/notas->wire [n]))))
      (is (true? (:casa-com-juridico (out/notas->wire [n] true))))
      (is (false? (:casa-com-juridico (out/notas->wire [] nil)))))))
