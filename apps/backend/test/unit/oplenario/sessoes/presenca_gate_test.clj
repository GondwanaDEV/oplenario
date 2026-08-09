(ns oplenario.sessoes.presenca-gate-test
  "UNIT (puro) — o GATE de ESTADO e a JANELA TEMPORAL do REGISTRO de presenca (Etapa 2 da chamada, §22.6 eixo C).

  O defeito que estas regras fecham e' JURIDICO, nao de higiene: ate' a Etapa 1 o sistema aceitava gravar
  `presenca_evento` numa sessao ja ENCERRADA. Como o quorum e' DERIVADO do ultimo evento por vereador
  (`logic/ultimos-eventos-por-vereador-q`), um evento gravado depois do fato muda o quorum de uma votacao
  JA REALIZADA — a ata diz uma coisa e o banco passa a dizer outra, sem trilha que as reconcilie.

  Duas defesas puras, aqui:
    1. ALLOWLIST de estado (`estados-sessao-aceita-presenca`). E' allowlist e nao complemento de
       `estados-sessao-fechada` de proposito: um estado NOVO no vocabulario (ou uma string corrompida)
       cai automaticamente na RECUSA. Complemento seria fail-open — o estado desconhecido passaria.
    2. JANELA do instante declarado (`motivo-recusa-de-presenca`): o `ocorrido-em` que o cliente declara
       tem de caber na vida da sessao e nao pode ser futuro. RECUSA-se, nao se 'clampa': grudar a hora no
       limite falsifica o fato registrado com aparencia de dado bom."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [oplenario.sessoes.logic :as logic])
  (:import (java.time Instant)))

(def ^:private t09 (Instant/parse "2026-06-30T09:00:00Z"))
(def ^:private t10 (Instant/parse "2026-06-30T10:00:00Z"))
(def ^:private t11 (Instant/parse "2026-06-30T11:00:00Z"))
(def ^:private t12 (Instant/parse "2026-06-30T12:00:00Z"))

(defn- sessao [estado & {:as extra}]
  (merge {:id (random-uuid) :estado estado} extra))

;; ---------- G1/G2/G3: o gate de ESTADO ----------

(deftest g1-estados-fechados-recusam-registro-de-presenca
  (testing "sessao que ja fechou nao aceita mais evento de presenca — o quorum dela ja foi para a ata"
    (is (false? (logic/aceita-registro-de-presenca? "encerrada")))
    (is (false? (logic/aceita-registro-de-presenca? "arquivada")))
    (is (false? (logic/aceita-registro-de-presenca? "nao_realizada")))))

(deftest g2-estado-desconhecido-recusa-fail-closed
  (testing "estado fora do vocabulario RECUSA (allowlist), nunca aceita por omissao"
    (is (false? (logic/aceita-registro-de-presenca? "em_recesso")) "estado inventado")
    (is (false? (logic/aceita-registro-de-presenca? "ABERTA")) "caixa alta nao e' o vocabulario")
    (is (false? (logic/aceita-registro-de-presenca? "")) "string vazia")
    (is (false? (logic/aceita-registro-de-presenca? nil)) "estado ausente (linha corrompida / campo nao lido)")))

(deftest g3-estados-vivos-aceitam-registro-de-presenca
  (testing "a sessao viva aceita — inclusive `agendada`: a chamada de quorum PRECEDE a abertura"
    (is (true? (logic/aceita-registro-de-presenca? "agendada")))
    (is (true? (logic/aceita-registro-de-presenca? "aberta")))
    (is (true? (logic/aceita-registro-de-presenca? "suspensa")))))

(deftest vocabulario-de-estado-e-particionado
  ;; guarda contra o drift silencioso: um estado NOVO em `estados-sessao` que ninguem classificar aparece
  ;; aqui como falha, em vez de cair na recusa sem que nenhum humano tenha decidido isso.
  (is (= logic/estados-sessao
         (set/union logic/estados-sessao-aceita-presenca logic/estados-sessao-fechada))
      "todo estado do vocabulario esta' classificado como 'aceita presenca' ou 'ja fechou'")
  (is (empty? (set/intersection logic/estados-sessao-aceita-presenca logic/estados-sessao-fechada))
      "nenhum estado e' as duas coisas"))

;; ---------- a JANELA do instante declarado ----------

(deftest janela-aceita-o-instante-dentro-da-sessao-viva
  (is (nil? (logic/motivo-recusa-de-presenca (sessao "aberta" :aberta-em t10) t11 t12))
      "aberta as 10h, evento as 11h, agora 12h -> nada a recusar")
  (is (nil? (logic/motivo-recusa-de-presenca (sessao "aberta" :aberta-em t10) t10 t12))
      "o proprio instante de abertura e' aceito (borda inclusiva)")
  (is (nil? (logic/motivo-recusa-de-presenca (sessao "agendada") t09 t12))
      "sessao ainda `agendada` nao tem aberta_em: o unico limite inferior e' inexistente"))

(deftest janela-recusa-estado-que-nao-aceita
  (is (= :estado-nao-aceita-presenca
         (logic/motivo-recusa-de-presenca (sessao "encerrada" :aberta-em t10 :encerrada-em t11) t10 t12))
      "o gate de estado tem precedencia: nem se olha a janela"))

(deftest janela-recusa-instante-no-futuro
  (is (= :instante-no-futuro
         (logic/motivo-recusa-de-presenca (sessao "aberta" :aberta-em t10) t12 t11))
      "declarar um fato que ainda nao aconteceu")
  (is (nil? (logic/motivo-recusa-de-presenca (sessao "aberta" :aberta-em t10) t11 t11))
      "o instante == agora e' aceito (borda inclusiva)"))

(deftest janela-recusa-instante-antes-da-abertura
  (is (= :instante-antes-da-abertura
         (logic/motivo-recusa-de-presenca (sessao "aberta" :aberta-em t10) t09 t12))
      "sessao aberta as 10h nao recebe presenca datada das 9h — retroagir a janela e' falsificar o fato"))

(deftest janela-recusa-instante-apos-o-encerramento
  ;; Segunda tranca: hoje o CHECK `sessao_encerrada_em_exige_estado` (mig 0026) impede uma sessao VIVA de
  ;; ter `encerrada_em`, entao este ramo e' inalcancavel pelo banco. Ele existe para o dia em que a
  ;; allowlist de estado crescer (ex.: um estado 'reaberta') e a janela virar o unico limite.
  (is (= :instante-apos-o-encerramento
         (logic/motivo-recusa-de-presenca (sessao "aberta" :aberta-em t09 :encerrada-em t10) t11 t12))))

;; ---------- a mensagem ACIONAVEL (o corpo do 409) ----------

(deftest mensagem-de-recusa-diz-o-estado-e-o-que-fazer
  (let [s (sessao "encerrada" :aberta-em t10 :encerrada-em t11)
        msg (logic/mensagem-de-recusa-de-presenca :estado-nao-aceita-presenca s t12)]
    (is (string? msg))
    (is (str/includes? msg "encerrada") "a mensagem nomeia o estado ATUAL da sessao"))
  (let [s (sessao "aberta" :aberta-em t10)]
    (is (str/includes? (logic/mensagem-de-recusa-de-presenca :instante-antes-da-abertura s t12)
                       "2026-06-30T10:00:00Z")
        "a mensagem mostra o limite violado, nao so' que houve violacao")
    (is (str/includes? (logic/mensagem-de-recusa-de-presenca :instante-no-futuro s t12)
                       "2026-06-30T12:00:00Z")
        "a mensagem mostra o relogio do servidor")))
