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

(deftest pedido-de-rascunho-em-curso
  (let [agora (java.time.Instant/parse "2026-09-26T21:10:00Z")
        pedido (fn [min] {:situacao "solicitado" :solicitado-em (.minusSeconds agora (* 60 min))})]
    (is (logic/pode-pedir-rascunho? nil agora) "nunca pediu")
    (is (not (logic/pode-pedir-rascunho? (pedido 5) agora)) "a IA esta' redigindo")
    (is (logic/pode-pedir-rascunho? (pedido 31) agora) "sem resposta ha' mais de 30 min: pode pedir de novo")
    (is (logic/pode-pedir-rascunho? (assoc (pedido 1) :situacao "pronto") agora))
    (is (logic/pode-pedir-rascunho? (assoc (pedido 1) :situacao "falhou") agora)))
  (is (not (logic/sessao-vai-para-ia? {:tipo-sessao "secreta"})))
  (is (logic/sessao-vai-para-ia? {:tipo-sessao "ordinaria"})))

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
                 {"texto" "Ata." "origem-redacao" "gerada_automaticamente"}
                 {"texto" "Ata." "origem-redacao" "gerada_automaticamente" "rascunho-id" "nao-e-uuid"}
                 {"texto" "Ata." "motivo-retificacao" 7}]]
    (is (= :validacao/invalido (tipo-do-erro #(in-ata/publicar->dominio corpo))) (pr-str (if (map? corpo) (keys corpo) corpo)))))
