(ns oplenario.admin-sistema.logic-test
  "ADR-0018 (fatia 1), puro: quem corta na hora, o prazo da 2a aprovacao do incidente e o efeito da aprovacao."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.admin-sistema.logic :as logic])
  (:import (java.time Duration Instant)))

(def t0 (Instant/parse "2026-09-30T12:00:00Z"))

(deftest motivos-fechados
  (is (= #{"inadimplencia" "pedido_da_casa" "ordem_judicial" "incidente_de_seguranca"} logic/motivos-de-suspensao))
  (is (not (contains? logic/motivos-de-suspensao "outro")) "\"outro\" nao entra: forca a nomear o motivo"))

(deftest so-ordem-judicial-e-incidente-cortam-a-sessao
  (is (logic/corta-na-hora? "ordem_judicial"))
  (is (logic/corta-na-hora? "incidente_de_seguranca"))
  (is (not (logic/corta-na-hora? "inadimplencia")))
  (is (not (logic/corta-na-hora? "pedido_da_casa"))))

(deftest o-incidente-tem-24h-para-a-segunda-aprovacao
  (is (= (.plus t0 (Duration/ofHours 24)) (logic/confirmar-ate "suspender" "incidente_de_seguranca" t0)))
  (is (nil? (logic/confirmar-ate "suspender" "inadimplencia" t0)) "os outros motivos nao suspendem sem o 2o operador")
  (is (nil? (logic/confirmar-ate "encerrar" "pedido_da_casa" t0)))
  (let [p {:estado "aguardando" :confirmar-ate (.plus t0 (Duration/ofHours 24))}]
    (is (not (logic/incidente-vencido? p (.plus t0 (Duration/ofHours 23)))))
    (is (logic/incidente-vencido? p (.plus t0 (Duration/ofHours 24))) "no limite ja' venceu")
    (is (not (logic/incidente-vencido? (assoc p :estado "aprovado") (.plus t0 (Duration/ofHours 48)))))
    (is (not (logic/incidente-vencido? {:estado "aguardando"} (.plus t0 (Duration/ofDays 30)))) "sem prazo nao vence")))

(deftest o-efeito-da-aprovacao
  (testing "sem sessao em curso: agora"
    (is (= :imediato (logic/efeito-da-aprovacao {:acao "suspender" :motivo "inadimplencia"} false))))
  (testing "com sessao em curso: espera o encerramento, salvo ordem judicial"
    (is (= :agendado (logic/efeito-da-aprovacao {:acao "suspender" :motivo "inadimplencia"} true)))
    (is (= :agendado (logic/efeito-da-aprovacao {:acao "encerrar" :motivo "fim_de_contrato"} true)))
    (is (= :imediato (logic/efeito-da-aprovacao {:acao "suspender" :motivo "ordem_judicial"} true))))
  (testing "o incidente ja' suspendeu: a aprovacao so' confirma"
    (is (= :ja-efetivado (logic/efeito-da-aprovacao {:acao "suspender" :motivo "incidente_de_seguranca" :efetivado-em t0} true)))))

(deftest o-motivo-que-a-casa-passa-a-ter
  (is (= "inadimplencia" (logic/motivo-da-casa {:acao "suspender" :motivo "inadimplencia"})))
  (is (= "encerramento_em_curso" (logic/motivo-da-casa {:acao "encerrar" :motivo "fim_de_contrato"}))))
