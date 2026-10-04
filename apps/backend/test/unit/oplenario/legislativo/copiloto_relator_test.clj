(ns oplenario.legislativo.copiloto-relator-test
  "Unit: ADR-0019 fatia 2 (Eixo 5) — o core CONFERE o rascunho da analise que a IA devolve antes de mostrar: so' citacoes da
  propria materia ou de dispositivo da Casa, status conhecido, texto limpo e pontos a confirmar recalculados aqui, e
  incerteza 'normal' so' com tudo conferido e normas publicadas. O contrato de saida (wire/out) valida o resultado."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.legislativo.adapters.out.parecer :as out]
            [oplenario.legislativo.controllers :as controllers]
            [oplenario.legislativo.logic :as logic]))

(def ^:private pid (random-uuid))

(def ^:private boa
  {:analise {:texto (str "A proposição tem por objeto hortas [[materia:" pid " | Institui hortas]].\n\n"
                         "Compete ao Município legislar [[norma:n1#art11 | Compete ao Município legislar]]. "
                         "[confirmar: se a iniciativa é do Prefeito]")
             :citacoes [{:fonte-id (str "materia:" pid) :rotulo "Projeto de Lei nº 12/2026" :trecho "Institui hortas"
                         :status "conferida"}
                        {:fonte-id "norma:n1#art11" :rotulo "Lei Orgânica do Município, art. 11 (consolidada até 30/06/2026)"
                         :trecho "Compete ao Município legislar" :status "conferida"}]
             :paragrafos-sem-fonte [] :incerteza "normal" :modelo "fake-1"
             :pontos-a-confirmar ["FORJADO"]}
   :normas "citadas" :indisponivel nil})

(deftest pontos-a-confirmar-na-ordem
  (is (= ["a", "o quórum"] (logic/pontos-a-confirmar "x [confirmar: a] y [ Confirmar :  o quórum ] z")))
  (is (= [] (logic/pontos-a-confirmar nil))))

(deftest conferir-analise-limpa-o-texto-e-recalcula-os-pontos
  (let [r (controllers/conferir-analise pid true boa)
        a (:analise r)]
    (is (not (re-find #"\[\[" (:texto a))) "as marcas de citacao ficam na tela, nunca no campo Analise")
    (is (re-find #"Compete ao Município legislar\." (:texto a)))
    (is (= ["se a iniciativa é do Prefeito"] (:pontos-a-confirmar a)) "recalculados pelo core, nunca o eco da IA")
    (is (= "citadas" (:normas r)))
    (is (= "normal" (:incerteza a)) "tudo conferido, normas publicadas e a IA disse 'normal'")
    (is (= [(str "materia:" pid) "norma:n1#art11"] (mapv :fonte-id (:citacoes a))))
    (is (= r (out/copiloto->wire r)) "o contrato de saida aceita o resultado inteiro")))

(deftest conferir-analise-leva-o-id-da-execucao-na-ia-para-o-reporte-de-erro
  ;; 8.4: o id e' o da execucao NO SATELITE (o que `POST /ia/execucoes/:id/reportes` espera); sem id, sem campo
  (let [com (controllers/conferir-analise pid true (assoc-in boa [:analise :execucao-id] "exec-ia-7"))
        sem (controllers/conferir-analise pid true boa)]
    (is (= "exec-ia-7" (get-in com [:analise :execucao-ia])))
    (is (= com (out/copiloto->wire com)) "o contrato de saida carrega o id")
    (is (not (contains? (:analise sem) :execucao-ia)) "sem id da IA, nada e' inventado")
    (is (= sem (out/copiloto->wire sem)))))

(deftest conferir-analise-descarta-o-que-nao-e-desta-materia-nem-da-casa
  (let [forjadas [{:fonte-id (str "materia:" (random-uuid)) :trecho "outra" :status "conferida"}
                  {:fonte-id "https://exemplo.com" :trecho "x" :status "conferida"}
                  {:fonte-id "norma:n1#art11" :trecho "x" :status "inventado"}
                  {:fonte-id "norma:n2#art40" :trecho "Regimento" :status "fonte_nao_lida"}]
        r (controllers/conferir-analise pid true (update-in boa [:analise :citacoes] into forjadas))
        a (:analise r)]
    (is (= [(str "materia:" pid) "norma:n1#art11" "norma:n2#art40"] (mapv :fonte-id (:citacoes a))))
    (is (= "revisar_com_atencao" (:incerteza a)) "uma citacao nao conferida basta para revisar com atencao")))

(deftest sem-normas-publicadas-e-sempre-revisar-com-atencao
  (let [r (controllers/conferir-analise pid false boa)]
    (is (= "sem-normas" (:normas r)) "o que vale e' o que o CORE sabe da Casa, nao o eco do satelite")
    (is (= "revisar_com_atencao" (get-in r [:analise :incerteza])))))

(deftest sem-dispositivo-citado-e-sem-texto
  (testing "normas publicadas, mas so' a materia citada"
    (let [r (controllers/conferir-analise pid true (update-in boa [:analise :citacoes] #(vec (take 1 %))))]
      (is (= "sem-dispositivo" (:normas r)))))
  (testing "paragrafo sem fonte: revisar"
    (is (= "revisar_com_atencao"
           (get-in (controllers/conferir-analise pid true (assoc-in boa [:analise :paragrafos-sem-fonte] [1]))
                   [:analise :incerteza]))))
  (testing "a IA nao rascunhou: sem analise, com a mensagem R-IA-1 que veio"
    (let [r (controllers/conferir-analise pid true {:analise nil :indisponivel {:motivo "cota" :mensagem "cota da Casa"}})]
      (is (= {:analise nil :normas "sem-dispositivo" :indisponivel "cota da Casa"} r))
      (is (= r (out/copiloto->wire r)))))
  (testing "texto so' de marcas vira sem analise"
    (is (nil? (:analise (controllers/conferir-analise pid true (assoc-in boa [:analise :texto] "  [[norma:n1#a | x]] "))))))
  (testing "texto com teto"
    (is (= 20000 (count (get-in (controllers/conferir-analise pid true (assoc-in boa [:analise :texto] (apply str (repeat 30000 "a"))))
                                [:analise :texto]))))))
