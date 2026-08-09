(ns oplenario.sessoes.chamada-revisao-test
  "UNIT (puro) — REGRESSAO dos achados da revisao adversarial da Etapa 2 da chamada (§22.6 eixo C).

  Cada deftest aqui reproduz o CENARIO DE FALHA de um achado, com o vocabulario que o cenario descreve.
  Nao sao testes de higiene: sao a prova de que o defeito existia e de que a correcao o fecha.

    R1 (MAJOR) o piso da janela era `aberta_em` -> a hora REAL de chegada de quem entrou antes do martelo
               era irregistravel, e a rota de LOTE recusava a chamada inteira por causa dela.
    R2 (MAJOR) o denominador CONGELADO no ato de chamada saia de uma conta roster-only, que DIVERGE do
               denominador que `GET /chamada` publica no caso que o modulo documenta como real
               (licenciado presente).
    R3 (MEDIO) tres relogios (browser/JVM/Postgres) comparados com desigualdade estrita e tolerancia zero.
    R4 (MENOR) `agendada` nao tinha piso NENHUM — o estado em que a chamada de fato acontece.
    R5 (MENOR) as duas tabelas de mensagem degradavam em silencio para um motivo novo do gate."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [oplenario.sessoes.logic :as logic])
  (:import (java.time Instant)))

;; America/Fortaleza = UTC-3, sem horario de verao. O dia civil 2026-06-30 comeca em 2026-06-30T03:00:00Z.
(def ^:private abertura-14h    (Instant/parse "2026-06-30T17:00:00Z")) ; 14:00 local
(def ^:private chegada-13h48   (Instant/parse "2026-06-30T16:48:00Z")) ; 13:48 local — ANTES do martelo
(def ^:private agora-14h03     (Instant/parse "2026-06-30T17:03:00Z")) ; 14:03 local
(def ^:private vespera-17h     (Instant/parse "2026-06-29T20:00:00Z")) ; 17:00 local do dia ANTERIOR
(def ^:private agendada-10h    (Instant/parse "2026-06-30T13:00:00Z")) ; 10:00 local

(defn- sessao [estado & {:as extra}]
  (merge {:id (random-uuid) :estado estado} extra))

;; ---------- R1: o piso da janela e' o DIA CIVIL da sessao, nao `aberta_em` ----------

(deftest r1-presenca-anterior-a-abertura-no-mesmo-dia-e-aceita
  (testing "a Mesa abre as 14:00 e o secretario conduz a chamada as 14:03 com as horas da folha (13:48)"
    (is (nil? (logic/motivo-recusa-de-presenca
               (sessao "aberta" :aberta-em abertura-14h :agendada-para agendada-10h)
               chegada-13h48 agora-14h03))
        "chegou 13:48, sessao aberta 14:00: e' o caso NORMAL da chamada ao vivo — nao pode ser recusado")))

(deftest r1-presenca-de-outro-dia-e-recusada
  (testing "o piso continua existindo: o que ele barra e' o fato de OUTRO dia, nao o de antes do martelo"
    (is (= :instante-fora-do-dia-da-sessao
           (logic/motivo-recusa-de-presenca
            (sessao "aberta" :aberta-em abertura-14h :agendada-para agendada-10h)
            vespera-17h agora-14h03))
        "presenca datada da vespera numa sessao de hoje e' indistinguivel de falsificacao")))

;; ---------- R4: `agendada` tambem tem piso ----------

(deftest r4-sessao-agendada-tem-piso-pelo-dia-da-sessao
  (testing "o estado em que a chamada de quorum acontece nao pode ser o unico sem limite inferior"
    (is (= :instante-fora-do-dia-da-sessao
           (logic/motivo-recusa-de-presenca (sessao "agendada" :agendada-para agendada-10h)
                                            vespera-17h agora-14h03))
        "sessao ainda agendada: o piso sai de `agendada_para`, nao de `aberta_em` (que e' nulo)")
    (is (nil? (logic/motivo-recusa-de-presenca (sessao "agendada" :agendada-para agendada-10h)
                                               chegada-13h48 agora-14h03))
        "chegada adiantada NO DIA da sessao continua legitima")))

(deftest r4-sessao-sem-marco-nenhum-nao-tem-piso
  ;; caminho NORMAL da API (`agendada-para` e' opcional em wire/in/AgendarSessao): sem nenhum dos dois marcos
  ;; nao ha' dia civil a partir do qual medir. Fica sem piso — documentado, nao esquecido.
  (is (nil? (logic/motivo-recusa-de-presenca (sessao "agendada") vespera-17h agora-14h03))))

;; ---------- R3: tolerancia de relogio ----------

(deftest r3-skew-pequeno-do-relogio-do-cliente-nao-derruba-a-chamada
  (testing "estacao da secretaria adiantada 40s — o secretario clica nome a nome e TUDO voltava 409"
    (is (nil? (logic/motivo-recusa-de-presenca
               (sessao "aberta" :aberta-em abertura-14h :agendada-para agendada-10h)
               (.plusSeconds agora-14h03 40) agora-14h03))
        "40s a frente do relogio da JVM cabe na tolerancia — nao e' declaracao de fato futuro")))

(deftest r3-futuro-alem-da-tolerancia-continua-recusado
  (is (= :instante-no-futuro
         (logic/motivo-recusa-de-presenca
          (sessao "aberta" :aberta-em abertura-14h :agendada-para agendada-10h)
          (.plusSeconds agora-14h03 (+ 60 (.toSeconds logic/tolerancia-de-relogio))) agora-14h03))
      "passada a tolerancia, declarar um fato que ainda nao aconteceu continua sendo recusa"))

;; ---------- R2: o denominador congelado e' o MESMO que a leitura publica ----------

(defn- membro [id estado-mandato]
  {:vereador-id id :nome (str "Vereador " id) :nome-parlamentar nil :partido nil
   :estado-mandato estado-mandato :cargo-mesa nil})

(deftest r2-denominador-congelado-casa-com-o-publicado-no-caso-do-licenciado-presente
  (testing "Casa de 12 cadeiras; X consta `licenciado` no cadastro (licenca nao encerrada na migracao) mas
            reassumiu e ESTA no plenario — o caso que `estado-de-presenca` documenta como real"
    (let [x       (random-uuid)
          roster  (into [(membro x "licenciado")]
                        (mapv #(membro % "vigente") (repeatedly 11 random-uuid)))
          presencas [{:vereador-id x :tipo "entrada" :modalidade "plenario"}]
          publicado (:membros-da-casa
                     (logic/contar-quorum
                      (mapv :linha (logic/derivar-linhas-da-chamada roster presencas []))))
          congelado (logic/membros-da-casa-da-chamada roster presencas [])]
      (is (= 12 publicado) "quem esta no plenario esta na Casa — a decisao ja tomada em `contar-quorum`")
      (is (= publicado congelado)
          "o numero que vai para o registro APPEND-ONLY tem de ser o mesmo que a tela publica"))))

(deftest r2-sem-evento-o-licenciado-continua-fora-do-denominador
  (let [x      (random-uuid)
        roster (into [(membro x "licenciado")]
                     (mapv #(membro % "vigente") (repeatedly 11 random-uuid)))]
    (is (= 11 (logic/membros-da-casa-da-chamada roster [] []))
        "sem evento positivo, o licenciado nao compoe a Casa (quem compoe e' o suplente)")))

(deftest r2-presenca-sem-assento-nao-entra-no-denominador-congelado
  (let [roster (mapv #(membro % "vigente") (repeatedly 9 random-uuid))]
    (is (= 9 (logic/membros-da-casa-da-chamada
              roster [{:vereador-id (random-uuid) :tipo "entrada" :modalidade "plenario"}] []))
        "um evento nao cria cadeira — mesma regra de `contar-quorum`, e nao uma conta a parte")))

(deftest r2-a-uniao-derivada-e-a-mesma-que-a-leitura-monta
  (testing "a funcao pura publica a UNIAO (roster + presencas orfas), na ordem que a leitura publica"
    (let [v1 (random-uuid) orfao (random-uuid)
          roster [(membro v1 "vigente")]
          linhas (logic/derivar-linhas-da-chamada
                  roster [{:vereador-id orfao :tipo "entrada" :modalidade "plenario"}] [])]
      (is (= 2 (count linhas)))
      (is (false? (:sem-assento (:linha (first linhas)))) "as do roster vem primeiro")
      (is (true? (:sem-assento (:linha (second linhas)))) "as orfas vao no fim"))))

;; ---------- R5: o vocabulario de motivo e as duas tabelas de mensagem nao driftam ----------

(deftest r5-todo-motivo-do-gate-tem-mensagem-propria-nas-duas-bordas
  (doseq [motivo logic/motivos-do-gate-de-presenca]
    (let [s (sessao "encerrada" :aberta-em abertura-14h :encerrada-em agora-14h03)
          fallback-presenca (str "recusado para esta sessao (" (name motivo) ")")
          fallback-chamada  (str "recusada para esta sessao (" (name motivo) ")")
          mp (logic/mensagem-de-recusa-de-presenca motivo s agora-14h03)
          mc (logic/mensagem-de-recusa-de-chamada motivo s agora-14h03)]
      (is (not (str/includes? mp fallback-presenca))
          (str "motivo " motivo " caiu no fallback generico de `mensagem-de-recusa-de-presenca`"))
      (is (not (str/includes? mc fallback-chamada))
          (str "motivo " motivo " caiu no fallback generico de `mensagem-de-recusa-de-chamada`")))))

(deftest r5-o-set-de-motivos-e-exatamente-o-que-o-gate-produz
  ;; guarda contra o drift na outra direcao: um motivo listado que o gate nunca emite, ou um motivo emitido
  ;; que ninguem listou. Enumera os caminhos do `cond` com sessoes construidas para cada um.
  (let [emitidos (set (keep identity
                            [(logic/motivo-recusa-de-presenca (sessao "encerrada" :encerrada-em agora-14h03)
                                                              chegada-13h48 agora-14h03)
                             (logic/motivo-recusa-de-presenca
                              (sessao "aberta" :aberta-em abertura-14h :agendada-para agendada-10h)
                              (.plusSeconds agora-14h03 86400) agora-14h03)
                             (logic/motivo-recusa-de-presenca
                              (sessao "aberta" :aberta-em abertura-14h :agendada-para agendada-10h)
                              vespera-17h agora-14h03)
                             (logic/motivo-recusa-de-presenca
                              (sessao "aberta" :aberta-em abertura-14h :agendada-para agendada-10h
                                      :encerrada-em (Instant/parse "2026-06-30T17:01:00Z"))
                              (Instant/parse "2026-06-30T17:02:00Z") agora-14h03)]))]
    (is (= logic/motivos-do-gate-de-presenca emitidos)
        (str "o set declarado e os motivos realmente emitidos divergiram: "
             (pr-str (set/difference logic/motivos-do-gate-de-presenca emitidos))
             " declarados-mas-nao-emitidos / "
             (pr-str (set/difference emitidos logic/motivos-do-gate-de-presenca))
             " emitidos-mas-nao-declarados"))))

(deftest r5-motivos-de-borda-fora-do-gate-tambem-tem-mensagem
  (is (not (str/includes? (logic/mensagem-de-recusa-de-presenca :sem-assento (sessao "aberta") agora-14h03)
                          "(sem-assento)"))
      "`:sem-assento` e' decidido no controller (precisa do roster), mas a mensagem mora no dominio")
  (is (not (str/includes? (logic/mensagem-de-recusa-de-chamada :casa-sem-membros (sessao "aberta") agora-14h03)
                          "(casa-sem-membros)"))
      "denominador zero tem mensagem acionavel propria, nao o fallback"))
