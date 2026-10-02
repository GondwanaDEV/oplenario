(ns oplenario.comunicacao.logic-test
  "UNIT (puro): ADR-0020 — a lista congelada, quem ve, o protocolo, o prazo de ciencia e os anexos."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.comunicacao.logic :as logic])
  (:import (java.time Instant LocalDate)))

(def ente (random-uuid))
(def remetente (random-uuid))
(def ana (random-uuid))
(def bruno (random-uuid))
(def carla (random-uuid))

(defn- ator [papeis & {:keys [tipo iid] :or {tipo "servidor" iid remetente}}]
  {:identidade-id iid :ente-id ente :tipo-vinculo tipo :papeis papeis})

(deftest a-lista-congelada
  (let [r (logic/congelar [{:tipo "setor" :alvo-id (random-uuid) :alvo-nome "Jurídico"
                            :pessoas [{:identidade-id ana :nome "Ana"} {:identidade-id remetente :nome "Eu"}]
                            :sem-acesso 0}
                           {:tipo "comissao" :alvo-id (random-uuid) :alvo-nome "Comissão de Finanças"
                            :pessoas [{:identidade-id bruno :nome "Bruno"} {:identidade-id ana :nome "Ana Lima"}]
                            :sem-acesso 2}
                           {:tipo "pessoa" :alvo-id carla :alvo-nome "Carla"
                            :pessoas [{:identidade-id carla :nome "Carla"}] :sem-acesso 0}]
                          remetente)]
    (testing "quem chega por dois caminhos aparece uma vez, com o primeiro; quem envia nao entra"
      (is (= [{:identidade-id ana :nome "Ana" :via "setor Jurídico"}
              {:identidade-id bruno :nome "Bruno" :via "Comissão de Finanças"}
              {:identidade-id carla :nome "Carla" :via "direto"}]
             (:destinatarios r))))
    (testing "os destinos guardam como foi enderecado, em ordem"
      (is (= [0 1 2] (mapv :ordem (:destinos r))))
      (is (= ["Jurídico" "Comissão de Finanças" "Carla"] (mapv :alvo-nome (:destinos r)))))
    (testing "os sem acesso sao contados para a tela avisar"
      (is (= 2 (:sem-acesso r))))))

(deftest via-de-cada-destino
  (is (= "direto" (logic/via {:tipo "vereador" :alvo-nome "X"})))
  (is (= "todos os setores" (logic/via {:tipo "todos" :alvo-nome "todos os setores"})))
  (is (= "setor Protocolo" (logic/via {:tipo "setor" :alvo-nome "Protocolo"}))))

(deftest destinos-repetidos-e-grupos
  (let [s (random-uuid)]
    (is (= [{:tipo "setor" :alvo-id s} {:tipo "todos" :alvo-id nil}]
           (logic/sem-destinos-repetidos [{:tipo "setor" :alvo-id s} {:tipo "setor" :alvo-id s} {:tipo "todos" :alvo-id nil}]))))
  (is (logic/tem-grupo? [{:tipo "pessoa"} {:tipo "comissao"}]))
  (is (not (logic/tem-grupo? [{:tipo "pessoa"} {:tipo "vereador"}]))))

(deftest quem-e-pessoa-da-casa-e-quem-ve
  (let [c {:ente-id ente :remetente-identidade-id remetente}]
    (is (logic/pessoa-da-casa? (ator #{})))
    (is (not (logic/pessoa-da-casa? (ator #{} :tipo "cidadao"))) "cidadao nunca")
    (is (not (logic/pessoa-da-casa? {:ente-id ente :papeis #{"agente_institucional"}})) "agente sem pessoa nunca")
    (testing "ve: destinatario, remetente, secretaria e admin"
      (is (logic/pode-ver? (ator #{}) c false) "quem enviou")
      (is (logic/pode-ver? (ator #{} :iid ana) c true) "destinataria")
      (is (logic/pode-ver? (ator #{"secretario"} :iid bruno) c false))
      (is (logic/pode-ver? (ator #{"admin_ente"} :iid bruno) c false))
      (is (not (logic/pode-ver? (ator #{"vereador"} :iid bruno) c false)) "outra pessoa da Casa nao")
      (is (not (logic/pode-ver? (assoc (ator #{"secretario"}) :ente-id (random-uuid)) c false)) "outra Casa nao"))
    (testing "o painel de leitura: so' remetente, secretaria e admin"
      (is (logic/pode-ver-leitura? (ator #{}) c))
      (is (not (logic/pode-ver-leitura? (ator #{} :iid ana) c)) "a destinataria nao ve a leitura dos outros")
      (is (logic/pode-ver-leitura? (ator #{"secretario"} :iid bruno) c)))))

(deftest o-protocolo
  (is (= "COM-2026-000123" (logic/protocolo 2026 123)))
  (is (= "COM-2027-1234567" (logic/protocolo 2027 1234567)))
  (is (= "comunicado:2026" (logic/escopo-da-numeracao 2026))))

(deftest o-prazo-de-ciencia
  (let [hoje (LocalDate/parse "2026-10-10")
        c {:exige-ciencia true :ciencia-ate (LocalDate/parse "2026-10-10")}]
    (testing "o dia do prazo ainda vale; vence no dia seguinte"
      (is (not (logic/prazo-vencido? c hoje)))
      (is (logic/prazo-vencido? c (.plusDays hoje 1))))
    (testing "vencido e' de quem ainda nao deu ciencia"
      (is (logic/vencido? c nil (.plusDays hoje 1)))
      (is (not (logic/vencido? c (Instant/now) (.plusDays hoje 1)))))
    (testing "pendentes vencidos nos enviados"
      (is (= 3 (logic/pendentes-vencidos (assoc c :destinatarios 5 :cientes 2) (.plusDays hoje 1))))
      (is (= 0 (logic/pendentes-vencidos (assoc c :destinatarios 5 :cientes 2) hoje))))
    (testing "o proximo prazo: o mais perto entre os que ainda nao venceram"
      (is (= (LocalDate/parse "2026-10-12")
             (logic/proxima-ciencia-ate [{:ciencia-ate (LocalDate/parse "2026-10-20")}
                                         {:ciencia-ate (LocalDate/parse "2026-10-09")}
                                         {:ciencia-ate nil}
                                         {:ciencia-ate (LocalDate/parse "2026-10-12")}]
                                        hoje))))
    (testing "o prazo pedido: so' com exige-ciencia, e nao no passado"
      (is (logic/prazo-valido? {:exige-ciencia true :ciencia-ate hoje} hoje))
      (is (not (logic/prazo-valido? {:exige-ciencia true :ciencia-ate (.minusDays hoje 1)} hoje)))
      (is (not (logic/prazo-valido? {:exige-ciencia false :ciencia-ate hoje} hoje)))
      (is (logic/prazo-valido? {:exige-ciencia false} hoje)))))

(deftest anexos
  (let [t (Instant/parse "2026-10-02T12:00:00Z")]
    (is (logic/na-janela-de-anexos? {:enviado-em t} (.plusSeconds t 600)))
    (is (not (logic/na-janela-de-anexos? {:enviado-em t} (.plusSeconds t 601)))))
  (is (= "comunicados/e/c/a" (logic/chave-do-anexo "e" "c" "a")))
  (is (= "relatorio.pdf" (logic/nome-de-arquivo "C:\\Users\\x\\relatorio.pdf")))
  (is (= "ataque.txt" (logic/nome-de-arquivo "../../\"ataque\u0000.txt")))
  (is (= "anexo" (logic/nome-de-arquivo "   ")))
  (is (= "application/pdf" (logic/tipo-de-midia "application/pdf; charset=binary")))
  (is (= "application/octet-stream" (logic/tipo-de-midia "isto nao e tipo"))))

(deftest totais-da-leitura
  (is (= {:destinatarios 3 :recebidos 2 :lidos 1 :cientes 1 :faltam-ler 2 :vencidos 1}
         (logic/totais-da-leitura [{:recebido-em 1 :lido-em 1 :ciente-em 1}
                                   {:recebido-em 1 :vencido true}
                                   {}]))))
