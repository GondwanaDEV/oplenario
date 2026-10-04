(ns oplenario.participacao.atendimento-logic-test
  "UNIT (puro) — o BALCAO interno de atendimento (6.1/6.2/5.10): o que esta' aberto, o prazo que vale (a MESMA
  derivacao do acompanhamento do cidadao — vencimento efetivo, dias corridos), as acoes cabiveis por estado e a
  identificacao da ouvidoria (so' identificada/anonima). Mais a prorrogacao do e-SIC (+10, LAI art. 11 §2º) e os
  gates de borda (situacao da fila, corpo da prorrogacao)."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.participacao.adapters.in.atendimento :as in]
            [oplenario.participacao.adapters.out.atendimento :as out]
            [oplenario.participacao.logic :as logic])
  (:import (java.time Instant LocalDate)))

(def ^:private hoje (LocalDate/parse "2026-07-10"))
(defn- d [s] (LocalDate/parse s))

(deftest o-que-esta-aberto-no-balcao
  (testing "e-SIC: pedido sem resposta, ou recurso sem decisao"
    (is (logic/aberto-no-balcao? :esic {:estado "protocolado"}))
    (is (logic/aberto-no-balcao? :esic {:estado "em_analise"}))
    (is (not (logic/aberto-no-balcao? :esic {:estado "respondido"})))
    (is (logic/aberto-no-balcao? :esic {:estado "respondido" :recurso {:estado "protocolado"}})
        "o recurso pendente devolve o pedido a fila: o servidor tem de decidir")
    (is (not (logic/aberto-no-balcao? :esic {:estado "respondido" :recurso {:estado "decidido"}}))))
  (testing "ouvidoria e LGPD: estado nao terminal"
    (is (logic/aberto-no-balcao? :ouvidoria {:estado "protocolada"}))
    (is (not (logic/aberto-no-balcao? :ouvidoria {:estado "arquivada"})))
    (is (not (logic/aberto-no-balcao? :ouvidoria {:estado "respondida"})))
    (is (logic/aberto-no-balcao? :lgpd {:estado "em_analise"}))
    (is (not (logic/aberto-no-balcao? :lgpd {:estado "respondida"})))))

(deftest o-prazo-que-vale-e-o-mesmo-do-cidadao
  (testing "vencimento efetivo (prorrogado vence o original) e dias corridos contra hoje"
    (is (= {:prazo-vigente (d "2026-07-20") :prorrogado false :dias-restantes 10}
           (logic/leitura-do-prazo {:vence-em (d "2026-07-20")} true hoje)))
    (is (= {:prazo-vigente (d "2026-08-19") :prorrogado true :dias-restantes 40}
           (logic/leitura-do-prazo {:vence-em (d "2026-07-20") :prorrogado-ate (d "2026-08-19")} true hoje))))
  (testing "vencido = negativo; o ultimo dia = 0"
    (is (= -3 (:dias-restantes (logic/leitura-do-prazo {:vence-em (d "2026-07-07")} true hoje))))
    (is (= 0 (:dias-restantes (logic/leitura-do-prazo {:vence-em hoje} true hoje)))))
  (testing "encerrado: o prazo nao corre mais"
    (is (nil? (:dias-restantes (logic/leitura-do-prazo {:vence-em (d "2026-07-07")} false hoje))))
    (is (= (d "2026-07-07") (:prazo-vigente (logic/leitura-do-prazo {:vence-em (d "2026-07-07")} false hoje)))))
  (testing "sem prazo (inconsistencia): nada, sem NPE"
    (is (= {:prazo-vigente nil :prorrogado false :dias-restantes nil} (logic/leitura-do-prazo nil true hoje)))))

(deftest no-esic-o-recurso-pendente-tem-o-relogio-que-vale
  (let [pedido {:vence-em (d "2026-07-01") :estado "cumprida"}
        rec    {:estado "protocolado" :prazo {:vence-em (d "2026-07-15")}}]
    (is (= (d "2026-07-15") (:vence-em (logic/prazo-vigente-esic {:prazo pedido :recurso rec}))))
    (is (= pedido (logic/prazo-vigente-esic {:prazo pedido :recurso (assoc rec :estado "decidido")})))
    (is (= pedido (logic/prazo-vigente-esic {:prazo pedido :recurso nil})))))

(deftest acoes-cabiveis-por-estado
  (let [pendente {:estado "pendente" :vence-em (d "2026-07-20")}]
    (testing "e-SIC"
      (is (= {:pode-responder true :pode-prorrogar true :recurso-pendente-id nil}
             (logic/acoes-do-balcao :esic {:estado "protocolado" :prazo pendente})))
      (is (= {:pode-responder true :pode-prorrogar false :recurso-pendente-id nil}
             (logic/acoes-do-balcao :esic {:estado "protocolado"
                                           :prazo (assoc pendente :prorrogado-ate (d "2026-07-30"))}))
          "prorrogar so' uma vez")
      (is (= {:pode-responder true :pode-prorrogar false :recurso-pendente-id nil}
             (logic/acoes-do-balcao :esic {:estado "protocolado" :prazo (assoc pendente :estado "vencida")}))
          "prazo ja' vencido nao prorroga (a CAS exige pendente)")
      (let [rid (random-uuid)]
        (is (= {:pode-responder false :pode-prorrogar false :recurso-pendente-id rid}
               (logic/acoes-do-balcao :esic {:estado "respondido" :prazo (assoc pendente :estado "cumprida")
                                             :recurso {:id rid :estado "protocolado"}})))))
    (testing "ouvidoria"
      (is (= {:pode-responder true :pode-arquivar true :pode-prorrogar true}
             (logic/acoes-do-balcao :ouvidoria {:estado "protocolada" :prazo pendente})))
      (is (= {:pode-responder false :pode-arquivar false :pode-prorrogar false}
             (logic/acoes-do-balcao :ouvidoria {:estado "arquivada" :prazo (assoc pendente :estado "cancelada")}))))
    (testing "LGPD"
      (is (= {:pode-responder true} (logic/acoes-do-balcao :lgpd {:estado "protocolada"})))
      (is (= {:pode-responder false} (logic/acoes-do-balcao :lgpd {:estado "respondida"}))))))

(deftest ouvidoria-so-diz-se-e-identificada
  (is (= "anonima" (logic/identificacao-da-manifestacao {:anonima true})))
  (is (= "identificada" (logic/identificacao-da-manifestacao {:anonima false :manifestante-identidade-id (random-uuid)}))))

(deftest prorrogacao-do-esic-soma-10-ao-original
  (is (= 10 logic/dias-prorrogacao-esic))
  (is (= (d "2026-07-31") (logic/vence-prorrogado-esic (d "2026-07-21")))))

(deftest situacao-da-fila
  (is (= "abertos" (in/situacao {})) "o default")
  (is (= "abertos" (in/situacao {:situacao ""})))
  (is (= "respondidos" (in/situacao {:situacao "respondidos"})))
  (is (= "todos" (in/situacao {:situacao "todos"})))
  (is (= :validacao/invalido (:tipo (ex-data (try (in/situacao {:situacao "vencidos"}) (catch Exception e e)))))))

(deftest corpo-da-prorrogacao-do-pedido
  (is (= {:justificativa "Busca no arquivo."} (in/coagir-prorrogar-pedido {"justificativa" "Busca no arquivo."})))
  (is (= {:justificativa "x"} (in/coagir-prorrogar-pedido {"justificativa" "x" "para_data" "2030-01-01"}))
      "a data nunca vem do cliente: a chave a mais e' descartada")
  (doseq [ruim [nil {} {"justificativa" "  "} {"justificativa" 3}]]
    (is (= :validacao/invalido (:tipo (ex-data (try (in/coagir-prorrogar-pedido ruim) (catch Exception e e)))))
        (pr-str ruim))))

(deftest a-saida-da-ouvidoria-nao-tem-lugar-para-o-manifestante
  (let [base {:id (random-uuid) :protocolo "OUV-2026-000001" :tipo "reclamacao" :assunto "Fila" :descricao "Demora."
              :identificacao "identificada" :estado "protocolada" :recibo-em (Instant/parse "2026-07-01T12:00:00Z")
              :aberto true :prazo-vigente (d "2026-07-31") :dias-restantes 21 :prorrogado false :historico []
              :acoes {:pode-responder true :pode-arquivar true :pode-prorrogar true}}]
    (is (= "identificada" (:identificacao (out/ouvidoria->wire base))))
    (is (not-any? #{:manifestante-identidade-id :requerente :nome :cpf-mascarado}
                  (keys (out/ouvidoria->wire (assoc base :manifestante-identidade-id (random-uuid)
                                                    :requerente {:nome "X" :cpf-mascarado "***.456.789-**"}))))
        "nada a mais passa: o adapter projeta por allowlist e nao ha' chave de pessoa no contrato")))

(deftest a-saida-do-esic-so-aceita-cpf-mascarado
  (let [base {:id (random-uuid) :protocolo "ESIC-2026-000001" :assunto "Contratos" :descricao "Lista."
              :estado "protocolado" :recibo-em (Instant/parse "2026-07-01T12:00:00Z") :aberto true
              :prazo-vigente (d "2026-07-21") :dias-restantes 11 :prorrogado false :historico [] :recurso nil
              :acoes {:pode-responder true :pode-prorrogar true :recurso-pendente-id nil}}]
    (is (= {:nome "Maria" :cpf-mascarado "***.456.789-**"}
           (:requerente (out/esic->wire (assoc base :requerente {:nome "Maria" :cpf-mascarado "***.456.789-**"})))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (out/esic->wire (assoc base :requerente {:nome "Maria" :cpf-mascarado "12345678909"})))
        "o contrato recusa um CPF inteiro: drift vira 500, nunca vazamento")))
