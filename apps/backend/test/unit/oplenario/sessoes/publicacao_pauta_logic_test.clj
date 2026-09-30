(ns oplenario.sessoes.publicacao-pauta-logic-test
  "Unit (puro): ADR-0019 fatia 3 — quem publica pela regra da Casa, 'alterada desde a publicacao', o tipo da proxima
  versao, a antecedencia (em minutos: 23h59 nao passa por 24h) e os avisos por materia."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.sessoes.logic :as logic])
  (:import (java.time Instant)))

(deftest regra-padrao-e-regra-gravada
  (is (= {:quem-publica "secretaria" :antecedencia-minima-horas nil :configurada false} (logic/regra-da-pauta nil)))
  (is (= ["mesa" 24 true] ((juxt :quem-publica :antecedencia-minima-horas :configurada)
                           (logic/regra-da-pauta {:quem-publica "mesa" :antecedencia-minima-horas 24})))))

(deftest quem-pode-publicar
  (let [pode (fn [regra papeis cargo] (:pode (logic/pode-publicar-pauta {:quem-publica regra} papeis cargo)))]
    (testing "secretaria: o papel, nao o cargo"
      (is (pode "secretaria" #{"secretario"} nil))
      (is (not (pode "secretaria" #{"vereador"} "presidente"))))
    (testing "presidente: so' o cargo de presidente — a secretaria prepara mas nao publica"
      (is (pode "presidente" #{"vereador"} "presidente"))
      (is (not (pode "presidente" #{"secretario"} nil)))
      (is (not (pode "presidente" #{"vereador"} "vice"))))
    (testing "primeiro_secretario: as duas grafias do 1o Secretario; 'secretario' sozinho e' ambiguo e nao entra"
      (is (pode "primeiro_secretario" #{"vereador"} "1_secretario"))
      (is (pode "primeiro_secretario" #{"vereador"} "primeiro_secretario"))
      (is (not (pode "primeiro_secretario" #{"vereador"} "secretario")))
      (is (not (pode "primeiro_secretario" #{"vereador"} "presidente"))))
    (testing "mesa: qualquer cargo na Mesa vigente"
      (is (pode "mesa" #{"vereador"} "2_secretario"))
      (is (not (pode "mesa" #{"vereador" "secretario"} nil))))
    (testing "regra desconhecida nega (fail-closed) e o motivo diz quem publica"
      (is (not (pode "quem-quiser" #{"secretario"} "presidente")))
      (is (= "Pela regra desta Casa, quem publica a pauta é o Presidente da Câmara."
             (:motivo (logic/pode-publicar-pauta {:quem-publica "presidente"} #{"secretario"} nil)))))))

(def ^:private a (random-uuid))
(def ^:private b (random-uuid))
(defn- it [id fase ordem] {:id id :fase fase :tipo-item "proposicao" :proposicao-id id :ordem ordem})

(deftest alterada-desde-a-publicacao
  (let [v {:snapshot [{:id (str a) :fase "ordem_do_dia" :tipo-item "proposicao" :proposicao-id (str a) :ordem 1}
                      {:id (str b) :fase "ordem_do_dia" :tipo-item "proposicao" :proposicao-id (str b) :ordem 2}]}]
    (is (false? (logic/alterada-desde-a-publicacao? nil [(it a "ordem_do_dia" 1)])) "sem publicacao nao ha' 'desde'")
    (is (false? (logic/alterada-desde-a-publicacao? v [(it a "ordem_do_dia" 5) (it b "ordem_do_dia" 9)]))
        "o numero da ordem muda sem mudar a sequencia: nao e' alteracao")
    (is (true? (logic/alterada-desde-a-publicacao? v [(it b "ordem_do_dia" 1) (it a "ordem_do_dia" 2)])) "inverteu")
    (is (true? (logic/alterada-desde-a-publicacao? v [(it a "ordem_do_dia" 1)])) "retirou")
    (is (true? (logic/alterada-desde-a-publicacao? v [(it a "expediente" 1) (it b "ordem_do_dia" 2)])) "mudou de fase")))

(deftest tipo-da-proxima
  (is (= "publicacao_inicial" (logic/tipo-da-proxima-publicacao [])))
  (is (= "publicacao_inicial" (logic/tipo-da-proxima-publicacao [{:publica true :tipo-versao "execucao_final"}])))
  (is (= "republicacao" (logic/tipo-da-proxima-publicacao [{:publica true :tipo-versao "publicacao_inicial"}]))))

(deftest antecedencia-em-minutos
  (let [inicio (Instant/parse "2026-10-07T12:00:00Z")
        s {:agendada-para inicio}
        r {:antecedencia-minima-horas 24}]
    (is (nil? (logic/antecedencia {:antecedencia-minima-horas nil} s inicio)) "sem regra, sem medida")
    (is (nil? (logic/antecedencia r {} inicio)) "sem data, sem medida (nunca um aviso inventado)")
    (is (= {:minimo-horas 24 :horas-reais 24 :cumprida true}
           (logic/antecedencia r s (Instant/parse "2026-10-06T12:00:00Z"))))
    (is (= {:minimo-horas 24 :horas-reais 23 :cumprida false}
           (logic/antecedencia r s (Instant/parse "2026-10-06T12:01:00Z"))) "23h59 nao e' 24h")
    (is (= -2 (:horas-reais (logic/antecedencia r {:agendada-para inicio :aberta-em (Instant/parse "2026-10-07T10:00:00Z")}
                                                (Instant/parse "2026-10-07T12:00:00Z"))))
        "ja' aberta: mede da abertura real")
    (is (= {:tipo "antecedencia-nao-cumprida" :minimo-horas 24 :horas-reais 23}
           (logic/aviso-de-antecedencia {:minimo-horas 24 :horas-reais 23 :cumprida false})))
    (is (nil? (logic/aviso-de-antecedencia {:minimo-horas 24 :horas-reais 30 :cumprida true})))))

(deftest avisos-das-materias
  (let [itens [(it a "ordem_do_dia" 1) (it b "ordem_do_dia" 2) {:id (random-uuid) :tipo-item "leitura"}]
        sit {a {:pareceres-emitidos 0 :pareceres-em-andamento 1 :pedidos-juridicos-pendentes 2}
             b {:pareceres-emitidos 1 :pareceres-em-andamento 0 :pedidos-juridicos-pendentes 0}}]
    (is (= [{:tipo "sem-parecer-comissao" :item-id a :proposicao-id a :pareceres-em-andamento 1}
            {:tipo "pedido-juridico-pendente" :item-id a :proposicao-id a :pedidos-pendentes 2}]
           (logic/avisos-das-materias itens sit)))
    (is (= [] (logic/avisos-das-materias itens {})) "materia fora do legislativo nao ganha aviso inventado")))
