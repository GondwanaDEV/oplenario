(ns oplenario.participacao.prorrogacao-contrato-test
  "UNIT (puro) — a PRORROGACAO que o requerente le no proprio protocolo (LAI art. 11 §2º: a prorrogacao exige
  justificativa expressa, 'da qual sera cientificado o requerente'). A saida de GET /portal/meus-protocolos ganha
  `prorrogacao` nos itens de e-SIC e de ouvidoria (nil quando nao houve; a LGPD nao tem prorrogacao). Contrato
  FECHADO: so' as duas datas, a justificativa e o instante — quem prorrogou (servidor) e o resto nunca saem."
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [malli.core :as m]
            [oplenario.participacao.adapters.out.meus-protocolos :as out]
            [oplenario.participacao.wire.out.meus-protocolos :as wire])
  (:import (java.time Instant LocalDate)))

(def ^:private servidor-id (random-uuid))

(def ^:private prorrogacao-de-dominio
  {:de-data (LocalDate/parse "2026-07-23") :para-data (LocalDate/parse "2026-08-02")
   :justificativa "Busca no arquivo morto: o acervo de 2019 ainda nao foi digitalizado."
   :prorrogado-em (Instant/parse "2026-07-08T15:30:00Z")
   ;; o que a tabela tem e NUNCA pode sair
   :prorrogado-por servidor-id :id (random-uuid) :ente-id (random-uuid) :objeto-id (random-uuid)})

(defn- base [extra]
  (merge {:id (random-uuid) :protocolo "ESIC-2026-000001" :estado "protocolada" :recibo-em (Instant/parse "2026-07-03T12:00:00Z")
          :vence-em (LocalDate/parse "2026-08-02") :dias-restantes 10 :resposta nil}
         extra))

(defn- saida [dominio]
  (out/meus-protocolos->wire (merge {:pedidos-esic [] :solicitacoes-lgpd [] :manifestacoes []} dominio)))

(deftest e-sic-e-ouvidoria-levam-a-prorrogacao-completa
  (let [w (saida {:pedidos-esic [(base {:assunto "Contratos" :estado "protocolado" :recurso nil :prorrogacao prorrogacao-de-dominio})]
                  :manifestacoes [(base {:protocolo "OUV-2026-000001" :tipo "reclamacao" :assunto "Fila"
                                         :prorrogacao prorrogacao-de-dominio})]})
        esperada {:de-data "2026-07-23" :para-data "2026-08-02"
                  :justificativa "Busca no arquivo morto: o acervo de 2019 ainda nao foi digitalizado."
                  :prorrogado-em "2026-07-08T15:30:00Z"}]
    (is (= esperada (get-in w [:pedidos-esic 0 :prorrogacao])))
    (is (= esperada (get-in w [:manifestacoes 0 :prorrogacao])))
    (testing "nada da tabela alem das quatro chaves: nem quem prorrogou, nem id, nem tenant"
      (let [texto (json/write-value-as-string w)]
        (is (not (.contains texto (str servidor-id))))
        (is (= #{:de-data :para-data :justificativa :prorrogado-em} (set (keys (get-in w [:pedidos-esic 0 :prorrogacao])))))))))

(deftest sem-prorrogacao-e-nil-por-chave
  (let [w (saida {:pedidos-esic [(base {:assunto "Contratos" :estado "protocolado" :recurso nil})]
                  :manifestacoes [(base {:protocolo "OUV-2026-000001" :tipo "sugestao" :assunto "Wi-fi"})]})]
    (is (contains? (get-in w [:pedidos-esic 0]) :prorrogacao) "presente por chave, como `recurso`")
    (is (nil? (get-in w [:pedidos-esic 0 :prorrogacao])))
    (is (contains? (get-in w [:manifestacoes 0]) :prorrogacao))
    (is (nil? (get-in w [:manifestacoes 0 :prorrogacao])))))

(deftest a-lgpd-nao-tem-prorrogacao
  (let [w (saida {:solicitacoes-lgpd [(base {:protocolo "LGPD-2026-000001" :tipo "acessar"
                                             :prorrogacao prorrogacao-de-dominio})]})]
    (is (not (contains? (get-in w [:solicitacoes-lgpd 0]) :prorrogacao))
        "a LGPD nao prorroga em V1: o item nao ganha a chave, mesmo que o dominio a mande")))

(deftest o-contrato-e-fechado-e-recusa-vazamento
  (let [valido (get-in (saida {:pedidos-esic [(base {:assunto "Contratos" :estado "protocolado" :recurso nil :prorrogacao prorrogacao-de-dominio})]})
                       [:pedidos-esic 0])]
    (is (m/validate wire/PedidoEsicItem valido))
    (testing "campo a mais dentro de `prorrogacao` (ex.: quem prorrogou) reprova o contrato"
      (is (not (m/validate wire/PedidoEsicItem (assoc-in valido [:prorrogacao :prorrogado-por] (str servidor-id))))))
    (testing "data fora do formato AAAA-MM-DD reprova (a tela nao recebe ISO de instante no lugar de data)"
      (is (not (m/validate wire/PedidoEsicItem (assoc-in valido [:prorrogacao :de-data] "2026-07-23T00:00:00Z")))))
    (testing "justificativa e' obrigatoria dentro da prorrogacao"
      (is (not (m/validate wire/PedidoEsicItem (update valido :prorrogacao dissoc :justificativa)))))))
