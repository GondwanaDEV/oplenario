(ns oplenario.normas.parser-test
  "B.4 (docs/25 Eixo 7.2): o texto de uma norma vira dispositivos, cada um com endereco estavel (fragmento no estilo
  LexML) e o rotulo com que se cita. Puro: nenhuma IA, nenhum banco."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.normas.logic :as logic]))

(def lom
  "LEI ORGÂNICA DO MUNICÍPIO DE EXEMPLO

Nós, representantes do povo, promulgamos a seguinte Lei Orgânica.

TÍTULO I
DA ORGANIZAÇÃO DO MUNICÍPIO

CAPÍTULO I
DISPOSIÇÕES PRELIMINARES

Art. 1º O Município de Exemplo integra a República Federativa do Brasil.

Art. 2º São Poderes do Município, independentes e harmônicos:
I - o Legislativo, exercido pela Câmara Municipal;
II – o Executivo, exercido pelo Prefeito.

CAPÍTULO II
DA CÂMARA MUNICIPAL

Art. 10. A Câmara reúne-se anualmente, de 15 de fevereiro a 30 de junho
e de 1º de agosto a 15 de dezembro.
§ 1º As reuniões marcadas para essas datas serão transferidas quando recaírem em feriados.
§ 2º A sessão legislativa não será interrompida sem:
I - aprovação do projeto de lei de diretrizes orçamentárias;
II - deliberação sobre:
a) as contas do Prefeito;
b) o orçamento anual.
Parágrafo único. Revogado.

Art. 10-A. A Câmara pode realizar sessões remotas.")

(defn- por-endereco [r] (into {} (map (juxt :endereco identity)) (:dispositivos r)))

(deftest quebra-em-dispositivos-com-endereco-e-rotulo
  (let [r (logic/dispositivos lom)
        d (por-endereco r)]
    (is (= ["preambulo" "art1" "art2" "art2_cpt_inc1" "art2_cpt_inc2" "art10" "art10_par1" "art10_par2"
            "art10_par2_inc1" "art10_par2_inc2" "art10_par2_inc2_ali1" "art10_par2_inc2_ali2" "art10_par1u" "art10-a"]
           (mapv :endereco (:dispositivos r))))
    (testing "rotulos de citacao"
      (is (= "art. 1º" (:rotulo (d "art1"))))
      (is (= "art. 10" (:rotulo (d "art10"))))
      (is (= "art. 10, § 2º, II, a" (:rotulo (d "art10_par2_inc2_ali1"))))
      (is (= "art. 2º, I" (:rotulo (d "art2_cpt_inc1"))))
      (is (= "art. 10, parágrafo único" (:rotulo (d "art10_par1u"))))
      (is (= "art. 10-A" (:rotulo (d "art10-a")))))
    (testing "o texto e' o do dispositivo, sem o rotulo, e linha quebrada continua o mesmo dispositivo"
      (is (= "O Município de Exemplo integra a República Federativa do Brasil." (:texto (d "art1"))))
      (is (= "A Câmara reúne-se anualmente, de 15 de fevereiro a 30 de junho e de 1º de agosto a 15 de dezembro."
             (:texto (d "art10"))))
      (is (= "o Executivo, exercido pelo Prefeito." (:texto (d "art2_cpt_inc2"))) "travessao tambem separa inciso"))
    (testing "pai e tipo"
      (is (= "art10_par2_inc2" (:pai (d "art10_par2_inc2_ali1"))))
      (is (= :alinea (:tipo (d "art10_par2_inc2_ali1"))))
      (is (nil? (:pai (d "art1"))))
      (is (= :preambulo (:tipo (d "preambulo")))))
    (testing "o agrupador (titulo/capitulo) em que o artigo esta"
      (is (= "TÍTULO I — DA ORGANIZAÇÃO DO MUNICÍPIO / CAPÍTULO II — DA CÂMARA MUNICIPAL" (:agrupador (d "art10"))))
      (is (= "TÍTULO I — DA ORGANIZAÇÃO DO MUNICÍPIO / CAPÍTULO I — DISPOSIÇÕES PRELIMINARES" (:agrupador (d "art1")))))
    (testing "a ordem e' a do texto"
      (is (= (range (count (:dispositivos r))) (map :ordem (:dispositivos r)))))
    (testing "salto na numeracao vira alerta para quem confere"
      (is (some #(re-find #"art\. 2º.*art\. 10" %) (:alertas r))))))

(deftest alertas-para-a-conferencia
  (testing "endereco repetido: fica o primeiro, e avisa"
    (let [r (logic/dispositivos "Art. 1º Um.\nArt. 1º Outro.")]
      (is (= 1 (count (filter #(= "art1" (:endereco %)) (:dispositivos r)))))
      (is (some #(re-find #"repetid" %) (:alertas r)))))
  (testing "inciso sem artigo nao tem onde ficar"
    (let [r (logic/dispositivos "I - solto no começo.\nArt. 1º Primeiro.")]
      (is (= ["preambulo" "art1"] (mapv :endereco (:dispositivos r))))
      (is (some #(re-find #"antes do primeiro artigo" %) (:alertas r)))))
  (testing "texto sem artigo nenhum"
    (is (some #(re-find #"(?i)nenhum artigo" %) (:alertas (logic/dispositivos "Um texto qualquer."))))))

(deftest numerais
  (is (= 4 (logic/romano->int "IV")))
  (is (= 49 (logic/romano->int "XLIX")))
  (is (nil? (logic/romano->int "ABC"))))
