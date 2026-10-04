(ns oplenario.participacao.complemento-logic-test
  "UNIT (puro) — o COMPLEMENTO DA RESPOSTA (ADR-0022): QUANDO cabe (so' em protocolo que a Casa ja' respondeu — resposta,
  indeferimento ou, no e-SIC, decisao do recurso; ouvidoria arquivada nao) e o que ele faz com a janela de anexos da Casa
  (reabre por mais 10 minutos; o limite de 5 e a janela do requerente nao mudam)."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.participacao.logic.anexo :as anexo]
            [oplenario.participacao.logic.complemento :as complemento])
  (:import (java.time Instant)))

(defn- t [s] (Instant/parse s))

(def ^:private resposta {:respondida-em (t "2026-07-03T12:00:00Z")})

(deftest cabe-so-onde-a-casa-ja-respondeu
  (testing "protocolo ainda aberto: nao cabe (e-SIC e LGPD)"
    (is (false? (complemento/pode-complementar? :esic {:estado "protocolado" :respostas []})))
    (is (false? (complemento/pode-complementar? :lgpd {:estado "protocolada" :respostas nil}))))
  (testing "respondido ou indeferido: cabe (a resposta e o indeferimento moram na mesma tabela)"
    (is (true? (complemento/pode-complementar? :esic {:estado "respondido" :respostas [resposta]})))
    (is (true? (complemento/pode-complementar? :esic {:estado "indeferido" :respostas [resposta]})))
    (is (true? (complemento/pode-complementar? :lgpd {:estado "indeferida" :respostas [resposta]}))))
  (testing "e-SIC com o recurso decidido: cabe, e o recurso pendente nao tira o direito (o pedido ja' foi respondido)"
    (is (true? (complemento/pode-complementar?
                :esic {:estado "respondido" :respostas [resposta]
                       :recurso {:respostas [{:respondida-em (t "2026-07-04T12:00:00Z")}]}})))
    (is (true? (complemento/pode-complementar?
                :esic {:estado "respondido" :respostas [resposta] :recurso {:estado "protocolado" :respostas []}}))))
  (testing "ouvidoria: depois da resposta cabe; manifestacao apenas arquivada nao (arquivar nao e' resposta de merito)"
    (is (true? (complemento/pode-complementar? :ouvidoria {:estado "respondida" :respostas [resposta]})))
    (is (false? (complemento/pode-complementar? :ouvidoria {:estado "arquivada" :respostas [resposta]})))
    (is (false? (complemento/pode-complementar? :ouvidoria {:estado "protocolada" :respostas []})))))

(deftest o-complemento-reabre-a-janela-de-anexos-da-casa
  (let [dados {:estado "respondido" :respostas [resposta]}
        com   (assoc dados :complementos [{:complementado-em (t "2026-07-03T15:00:00Z")}])]
    (testing "sem complemento, o ultimo ato e' a resposta; com, e' o complemento (o mais recente)"
      (is (= (t "2026-07-03T12:00:00Z") (anexo/ultimo-ato-de-resposta :esic dados)))
      (is (= (t "2026-07-03T15:00:00Z") (anexo/ultimo-ato-de-resposta :esic com))))
    (testing "tres horas depois da resposta a janela estava fechada; dentro dos 10 minutos do complemento, aberta"
      (is (false? (anexo/na-janela? (anexo/ultimo-ato-de-resposta :esic dados) (t "2026-07-03T15:00:00Z"))))
      (is (true? (anexo/na-janela? (anexo/ultimo-ato-de-resposta :esic com) (t "2026-07-03T15:10:00Z"))) "o minuto 10 vale")
      (is (false? (anexo/na-janela? (anexo/ultimo-ato-de-resposta :esic com) (t "2026-07-03T15:10:01Z")))))
    (testing "o limite de 5 anexos da Casa nao muda"
      (is (false? (anexo/pode-anexar? (anexo/ultimo-ato-de-resposta :esic com) 5 (t "2026-07-03T15:01:00Z"))))
      (is (true? (anexo/pode-anexar? (anexo/ultimo-ato-de-resposta :esic com) 4 (t "2026-07-03T15:01:00Z")))))
    (testing "a janela do REQUERENTE e' a do protocolo: o complemento da Casa nao a reabre"
      (is (false? (anexo/na-janela-do-requerente? (t "2026-07-03T11:00:00Z") (t "2026-07-03T15:01:00Z")))))
    (testing "complemento sem resposta (dado inconsistente) nao abre janela"
      (is (nil? (anexo/ultimo-ato-de-resposta :esic {:estado "protocolado" :respostas []
                                                     :complementos [{:complementado-em (t "2026-07-03T15:00:00Z")}]}))))))
