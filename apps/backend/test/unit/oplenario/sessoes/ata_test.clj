(ns oplenario.sessoes.ata-test
  "Faixa A / A.6a: as regras PURAS da ata (quem pode ter ata) e o gate de entrada da publicacao."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.sessoes.adapters.in.ata :as in-ata]
            [oplenario.sessoes.logic :as logic]))

(deftest so-sessao-que-gera-ata-e-ja-acabou-tem-ata
  (is (logic/pode-ter-ata? {:gera-ata-regimental true :estado "encerrada"}))
  (is (logic/pode-ter-ata? {:gera-ata-regimental true :estado "arquivada"}))
  (testing "a sessao ainda nao acabou (ou nao aconteceu)"
    (doseq [e ["agendada" "aberta" "suspensa" "nao_realizada"]]
      (is (not (logic/pode-ter-ata? {:gera-ata-regimental true :estado e})) e)))
  (testing "solene/especial nao geram ata regimental"
    (is (not (logic/pode-ter-ata? {:gera-ata-regimental false :estado "encerrada"})))))

(defn- tipo-do-erro [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:tipo (ex-data e)))))

(deftest gate-de-entrada-da-publicacao
  (is (= {:texto "Ata." :origem-redacao "redigida_externamente" :motivo-retificacao nil}
         (in-ata/publicar->dominio {"texto" "Ata."})) "origem padrao: redigida pela Casa")
  (is (= "Erro de digitacao" (:motivo-retificacao (in-ata/publicar->dominio {"texto" "Ata." "motivo-retificacao" "  Erro de digitacao "}))))
  (is (nil? (:motivo-retificacao (in-ata/publicar->dominio {"texto" "Ata." "motivo-retificacao" "   "})))
      "motivo so' de espacos = sem motivo (a regra da retificacao decide)")
  (doseq [corpo [nil [] {} {"texto" ""} {"texto" "   "} {"texto" 42}
                 {"texto" (apply str (repeat (inc logic/teto-texto-ata) "a"))}
                 {"texto" "Ata." "origem-redacao" "inventada"}
                 {"texto" "Ata." "motivo-retificacao" 7}]]
    (is (= :validacao/invalido (tipo-do-erro #(in-ata/publicar->dominio corpo))) (pr-str (if (map? corpo) (keys corpo) corpo)))))
