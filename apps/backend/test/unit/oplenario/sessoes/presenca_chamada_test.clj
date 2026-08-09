(ns oplenario.sessoes.presenca-chamada-test
  "UNIT (puro, sem banco) — a derivacao do estado de presenca por vereador (a CHAMADA) e a contagem de
  quorum sobre as linhas ja derivadas (§22.6 eixo C).

  O que estes testes protegem, em uma frase: a chamada e' a leitura que o servidor projeta no telao e que a
  policy de quorum consulta — chamar de 'ausente' quem tem justificativa PENDENTE e' acusacao falsa contra o
  vereador, e contar um LICENCIADO no denominador quebra o quorum da Casa inteira."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [oplenario.sessoes.logic :as logic]
            [oplenario.sessoes.models.presenca :as models]))

(def ^:private vid #uuid "00000000-0000-0000-0000-0000000000a1")

(defn- roster
  "Uma linha do roster como o seam de `cadastros` a entrega (vocabulario CRU do dono: `estado-mandato` e' o
  `cadastros.mandato.estado` do CHECK da mig 0010)."
  [& {:as sobrescrita}]
  (merge {:vereador-id vid :nome "Maria de Souza" :nome-parlamentar "Maria do Povo"
          :partido "PDT" :estado-mandato "vigente"}
         sobrescrita))

(defn- evento
  "Ultimo evento de presenca do vereador (vocabulario de `sessoes.logic`, espelho do CHECK da mig 0029/0056)."
  [tipo modalidade]
  {:tipo tipo :modalidade modalidade})

(defn- justificativa [estado] {:estado estado})

;; ---------- T1..T3: o ULTIMO evento manda ----------

(deftest t1-entrada-no-plenario-e-presente-plenario
  (is (= :presente-plenario
         (:estado (logic/estado-de-presenca (roster) (evento "entrada" "plenario") nil)))))

(deftest t2-mudanca-de-modalidade-para-remoto-e-presente-remoto
  (is (= :presente-remoto
         (:estado (logic/estado-de-presenca (roster) (evento "mudanca_modalidade" "remoto") nil)))))

(deftest t3-saida-e-ausente
  (is (= :ausente
         (:estado (logic/estado-de-presenca (roster) (evento "saida" "plenario") nil)))))

;; ---------- T4..T5: a justificativa, e a diferenca que NAO pode colapsar ----------

(deftest t4-sem-evento-com-justificativa-aprovada-e-ausente-justificado
  (is (= :ausente-justificado
         (:estado (logic/estado-de-presenca (roster) nil (justificativa "aprovada"))))))

(deftest t5-sem-evento-com-justificativa-pendente-nao-colapsa-em-injustificada
  (let [estado (:estado (logic/estado-de-presenca (roster) nil (justificativa "pendente")))]
    (is (= :ausente-justificativa-pendente estado))
    (is (not= :ausente estado)
        "pendente NAO e' injustificada — a Mesa ainda nao decidiu; chamar de injustificada acusa o vereador")))

;; ---------- T6..T7: o cadastro (licenca) e a inconsistencia que NAO se esconde ----------

(deftest t6-licenciado-sem-evento-e-licenciado-e-fica-fora-do-denominador
  (let [linha (logic/derivar-linha-chamada (roster :estado-mandato "licenciado") nil nil)]
    (is (= :licenciado (:estado linha)))
    (is (= {:presentes-plenario 0 :presentes-remoto 0 :membros-da-casa 0 :presencas-fora-do-roster 0}
           (logic/contar-quorum [linha]))
        "licenciado nao entra no denominador do quorum")))

(deftest t7-licenciado-com-evento-positivo-e-presente-e-sinaliza-inconsistencia
  (let [linha (logic/derivar-linha-chamada (roster :estado-mandato "licenciado")
                                           (evento "entrada" "plenario")
                                           nil)]
    (is (= :presente-plenario (:estado linha))
        "o fato observado (ele entrou) vence o cadastro")
    (is (true? (:inconsistencia-cadastro linha))
        "a inconsistencia e' EXIBIDA, nao escondida — numa Casa recem-migrada e' o sinal que o servidor precisa ver")
    (is (= {:presentes-plenario 1 :presentes-remoto 0 :membros-da-casa 1 :presencas-fora-do-roster 0}
           (logic/contar-quorum [linha]))
        "presente de verdade: conta no numerador E no denominador")))

;; ---------- T8: a contagem fecha ----------

(deftest t8-contar-quorum-fecha-plenario-mais-remoto
  (let [linhas [(logic/derivar-linha-chamada (roster) (evento "entrada" "plenario") nil)
                (logic/derivar-linha-chamada (roster) (evento "retorno" "plenario") nil)
                (logic/derivar-linha-chamada (roster) (evento "mudanca_modalidade" "remoto") nil)
                (logic/derivar-linha-chamada (roster) (evento "saida" "plenario") nil)
                (logic/derivar-linha-chamada (roster) nil (justificativa "aprovada"))
                (logic/derivar-linha-chamada (roster) nil (justificativa "pendente"))
                (logic/derivar-linha-chamada (roster :estado-mandato "licenciado") nil nil)]
        {:keys [presentes-plenario presentes-remoto membros-da-casa]} (logic/contar-quorum linhas)
        presentes (count (filter #(contains? logic/estados-chamada-presentes (:estado %)) linhas))]
    (is (= presentes (+ presentes-plenario presentes-remoto))
        "a soma dos dois numeradores e' exatamente o conjunto de presentes")
    (is (= 2 presentes-plenario))
    (is (= 1 presentes-remoto))
    (is (= 6 membros-da-casa) "7 linhas menos o licenciado")))

;; ---------- precedencia ESCRITA, nao inferida ----------

(deftest precedencia-evento-positivo-vence-justificativa-aprovada
  (is (= :presente-plenario
         (:estado (logic/estado-de-presenca (roster) (evento "entrada" "plenario") (justificativa "aprovada"))))
      "ele justificou e veio assim mesmo: quem esta no plenario esta presente"))

(deftest precedencia-evento-negativo-nao-vence-justificativa-aprovada
  (is (= :ausente-justificado
         (:estado (logic/estado-de-presenca (roster) (evento "saida" "plenario") (justificativa "aprovada"))))
      "saida nao e' fato positivo — a justificativa aprovada segue valendo"))

(deftest justificativa-indeferida-e-ausencia-simples
  (is (= :ausente
         (:estado (logic/estado-de-presenca (roster) nil (justificativa "indeferida"))))))

(deftest licenciado-com-evento-de-saida-continua-licenciado
  (let [linha (logic/derivar-linha-chamada (roster :estado-mandato "licenciado") (evento "saida" "plenario") nil)]
    (is (= :licenciado (:estado linha)))
    (is (false? (:inconsistencia-cadastro linha))
        "sair nao contradiz a licenca — so o fato POSITIVO contradiz")))

;; ---------- a REGRA DURA de vocabulario (o defeito que ja custou meses neste repo) ----------

(deftest vocabulario-forasteiro-falha-fechado-nunca-classifica-em-silencio
  (testing "tipo de evento que produtor nenhum emite"
    (is (thrown? clojure.lang.ExceptionInfo
                 (logic/estado-de-presenca (roster) (evento "presente" "plenario") nil))
        "'presente' nao esta no CHECK da mig 0029 — tem de EXPLODIR, nao virar :ausente mudo"))
  (testing "modalidade forasteira"
    (is (thrown? clojure.lang.ExceptionInfo
                 (logic/estado-de-presenca (roster) (evento "entrada" "videoconferencia") nil))))
  (testing "estado de justificativa forasteiro"
    (is (thrown? clojure.lang.ExceptionInfo
                 (logic/estado-de-presenca (roster) nil (justificativa "deferida")))))
  (testing "estado de mandato forasteiro (vocabulario do dono, cadastros)"
    (is (thrown? clojure.lang.ExceptionInfo
                 (logic/estado-de-presenca (roster :estado-mandato "suplente") nil nil))))
  (testing "os valores REAIS do dono passam"
    (doseq [e ["vigente" "licenciado" "cassado" "renunciado" "falecido" "concluido"]]
      (is (some? (logic/estado-de-presenca (roster :estado-mandato e) nil nil))
          (str "estado de mandato do CHECK da mig 0010 recusado: " e)))
    (is (some? (logic/estado-de-presenca (roster :estado-mandato nil) nil nil))
        "sem mandato cobrindo a data (LEFT JOIN LATERAL sem linha) e' nil legitimo, nao erro")))

;; ---------- o contrato de dominio ----------

;; ---------- REVISAO Etapa 1 (MAJOR): a presenca SEM ASSENTO no roster nao pode sumir ----------
;; O motor de votacao conta quorum sobre `presenca_evento` SOZINHO (relacoes/presenca — sem join a mandato).
;; A chamada cruza roster x eventos. Toda presenca cujo vereador nao esta no roster caia fora = a tela conta
;; um numero e a policy delibera com outro na MESMA votacao. A linha existe, e' marcada, entra no NUMERADOR
;; (para casar com o motor) e fica fora do DENOMINADOR (ele nao tem cadeira).

(deftest presenca-sem-assento-vira-linha-marcada-e-nunca-descarte-mudo
  (let [linha (logic/derivar-linha-sem-assento
                {:vereador-id vid :tipo "entrada" :modalidade "plenario"} nil)]
    (is (= :presente-plenario (:estado linha)) "o fato observado vale: ele esta no plenario")
    (is (true? (:sem-assento linha)) "marcada como sem assento no roster da data")
    (is (true? (:inconsistencia-cadastro linha))
        "evento sem cadeira e' contradicao cadastro-vs-fato: o servidor tem de VER para consertar")
    (is (nil? (:nome linha)) "a identidade vem do roster; sem assento nao ha' nome a inventar")))

(deftest quorum-conta-sem-assento-no-numerador-e-nao-no-denominador
  (let [com-assento (logic/derivar-linha-chamada (roster) (evento "entrada" "plenario") nil)
        orfa        (logic/derivar-linha-sem-assento
                      {:vereador-id vid :tipo "entrada" :modalidade "plenario"} nil)
        orfa-remota (logic/derivar-linha-sem-assento
                      {:vereador-id vid :tipo "retorno" :modalidade "remoto"} nil)
        q (logic/contar-quorum [com-assento orfa orfa-remota])]
    (is (= 2 (:presentes-plenario q)) "o sem-assento presente CONTA no numerador (e' o que o motor conta)")
    (is (= 1 (:presentes-remoto q)))
    (is (= 1 (:membros-da-casa q)) "denominador = so' quem tem cadeira; a Casa nao cresce por evento orfao")
    (is (= 2 (:presencas-fora-do-roster q))
        "e o numero e' PUBLICADO — fail-loud: a Mesa ve que ha' evento sem cadeira")))

(deftest linha-com-assento-nao-e-marcada-sem-assento
  (is (false? (:sem-assento (logic/derivar-linha-chamada (roster) nil nil)))
      "o campo e' uniforme (mapa fechado), nunca ausente numa linha e presente noutra"))

;; ---------- REVISAO Etapa 1 (MEDIO): o instante de avaliacao falha FECHADO ----------

(def ^:private t-encerrada #inst "2026-06-30T18:00:00.000-00:00")
(def ^:private t-agora     #inst "2026-06-30T20:00:00.000-00:00")

(deftest instante-de-avaliacao-congela-em-encerrada-em-quando-a-sessao-fechou
  (doseq [estado ["encerrada" "nao_realizada" "arquivada"]]
    (is (= (.toInstant ^java.util.Date t-encerrada)
           (logic/instante-de-avaliacao {:estado estado :encerrada-em (.toInstant ^java.util.Date t-encerrada)}
                                        (.toInstant ^java.util.Date t-agora)))
        (str "sessao " estado " avalia no instante em que fechou, nao em 'agora'"))))

(deftest instante-de-avaliacao-usa-agora-enquanto-a-sessao-esta-viva
  (doseq [estado ["agendada" "aberta" "suspensa"]]
    (is (= (.toInstant ^java.util.Date t-agora)
           (logic/instante-de-avaliacao {:estado estado :encerrada-em nil}
                                        (.toInstant ^java.util.Date t-agora))))))

(deftest instante-de-avaliacao-lanca-em-sessao-fechada-sem-encerrada-em
  ;; O CHECK `sessao_encerrada_em_obrigatoria` da mig 0026 NAO cobria 'arquivada' (a migration desta revisao
  ;; passa a cobrir). Enquanto o dado existir, um `instante` nil viraria `ocorrido_em <= NULL` -> zero linhas:
  ;; uma chamada FABRICADA (Casa inteira ausente, quorum zero) servida como leitura legitima, e ela vai p/ ata.
  (is (thrown? clojure.lang.ExceptionInfo
               (logic/instante-de-avaliacao {:estado "arquivada" :encerrada-em nil}
                                            (.toInstant ^java.util.Date t-agora)))
      "sessao fechada sem carimbo de encerramento -> EXPLODE, nunca uma chamada de instante NULL"))

(deftest linha-chamada-satisfaz-o-model-de-dominio
  (doseq [[rot ev ju] [[(roster) (evento "entrada" "plenario") nil]
                       [(roster :nome-parlamentar nil :partido nil) nil (justificativa "pendente")]
                       [(roster :estado-mandato "licenciado") nil nil]]]
    (let [linha (logic/derivar-linha-chamada rot ev ju)]
      (is (m/validate models/LinhaChamada linha)
          (str "LinhaChamada invalida: " (pr-str (m/explain models/LinhaChamada linha))))))
  (let [orfa (logic/derivar-linha-sem-assento {:vereador-id vid :tipo "entrada" :modalidade "remoto"} nil)]
    (is (m/validate models/LinhaChamada orfa)
        (str "linha sem assento invalida: " (pr-str (m/explain models/LinhaChamada orfa))))))
