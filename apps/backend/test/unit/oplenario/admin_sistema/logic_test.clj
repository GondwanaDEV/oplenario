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

;; ---------- ADR-0018 (fatia 2): encerrar ----------

(deftest encerrado-nao-tem-saida
  (is (logic/transicao-permitida? "suspenso" "encerrado"))
  (is (not (logic/transicao-permitida? "ativo" "encerrado")) "so' a Casa com o encerramento em curso encerra")
  (doseq [para ["provisionar" "ativo" "suspenso" "encerrado"]]
    (is (not (logic/transicao-permitida? "encerrado" para)) (str "encerrado -> " para))))

(deftest a-janela-de-guarda-de-90-dias
  (is (= (Instant/parse "2026-12-29T12:00:00Z") (logic/apagamento-possivel-em t0)))
  (is (nil? (logic/apagamento-possivel-em nil)))
  (is (not (logic/guarda-cumprida? t0 (.plus t0 (Duration/ofDays 89)))))
  (is (not (logic/guarda-cumprida? t0 (.minusSeconds (.plus t0 (Duration/ofDays 90)) 1))))
  (is (logic/guarda-cumprida? t0 (.plus t0 (Duration/ofDays 90))) "no instante exato, ja' pode")
  (is (not (logic/guarda-cumprida? nil (.plus t0 (Duration/ofDays 900)))) "sem confirmacao, nunca"))

(deftest a-confirmacao-que-vale-e-a-do-encerramento
  (let [desde (.plus t0 (Duration/ofDays 10))
        antes {:id 1 :confirmada-em t0 :solicitada-em t0}
        depois {:id 2 :confirmada-em (.plus desde (Duration/ofDays 1)) :solicitada-em desde}
        mais-nova {:id 3 :confirmada-em (.plus desde (Duration/ofDays 5)) :solicitada-em desde}
        sem {:id 4 :confirmada-em nil :solicitada-em desde}]
    (is (nil? (logic/confirmacao-do-encerramento [antes sem] desde)) "a portabilidade de antes nao abre a guarda")
    (is (= 3 (:id (logic/confirmacao-do-encerramento [antes depois mais-nova sem] desde))) "vale a mais recente")
    (is (nil? (logic/confirmacao-do-encerramento [depois] nil)) "sem encerramento, nenhuma vale")
    (testing "empate no instante: a gerada por ultimo"
      (let [a {:id :a :confirmada-em desde :solicitada-em t0}
            b {:id :b :confirmada-em desde :solicitada-em (.plus t0 (Duration/ofDays 1))}]
        (is (= :b (:id (logic/confirmacao-do-encerramento [b a] desde))))))))

(deftest pode-pedir-o-apagamento
  (let [casa {:estado "suspenso" :motivo-restricao "encerramento_em_curso"}
        conf {:confirmada-em t0}
        depois (.plus t0 (Duration/ofDays 91))]
    (is (logic/pode-pedir-apagamento? casa conf depois))
    (is (not (logic/pode-pedir-apagamento? casa conf (.plus t0 (Duration/ofDays 30)))) "guarda em curso")
    (is (not (logic/pode-pedir-apagamento? casa nil depois)) "sem confirmacao")
    (is (not (logic/pode-pedir-apagamento? {:estado "suspenso" :motivo-restricao "inadimplencia"} conf depois))
        "suspensa por outro motivo nao se apaga")
    (is (not (logic/pode-pedir-apagamento? {:estado "encerrado"} conf depois)))))

(deftest a-geracao-abandonada
  (is (not (logic/geracao-abandonada? {:estado "gerando" :solicitada-em t0} (.plus t0 (Duration/ofHours 5)))))
  (is (logic/geracao-abandonada? {:estado "gerando" :solicitada-em t0} (.plus t0 (Duration/ofHours 7))))
  (is (not (logic/geracao-abandonada? {:estado "pronta" :solicitada-em t0} (.plus t0 (Duration/ofDays 7))))))

(deftest o-operador-ve-o-manifesto-resumido
  (is (= {:arquivos {:itens 2} :bytes 10 :tabelas {:itens 3} :versao "1"}
         (logic/resumo-do-manifesto {:versao "1" :bytes 10 :arquivos ["a" "b"] :tabelas {:a 1 :b 2 :c 3}})))
  (is (= {} (logic/resumo-do-manifesto {:texto-longo (apply str (repeat 200 "x"))})) "texto longo nao passa")
  (is (nil? (logic/resumo-do-manifesto nil))))
