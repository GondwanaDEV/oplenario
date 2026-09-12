(ns oplenario.demo.compliance-test
  "INTEGRACAO (PG + MinIO real): `compliance/semear!` — a 5a semente narrativa, que enche o card 'saude
  institucional' do dashboard da Mesa (`/paineis/mesa`), ate' aqui ZERADO em toda demo.

  O QUE ESTE TESTE PROVA, e por que cada asercao existe:

  1. O placar sai do RUNTIME, nao da semente. A asercao forte nao e' 'o painel tem numero' — e' que o
     numero bate com a narrativa das competencias E que o veredito de cada obrigacao veio do fato
     `remessa_enviada` de verdade. Uma semente que fizesse `UPDATE ... SET estado='cumprida'` passaria
     num teste que so' contasse linhas; nao passa neste, que exige `veredito='conforme'` exatamente nas
     competencias com remessa aceita e `nao_conforme` nas outras.
  2. O vencimento veio do SWEEP. `pendente -> vencida` e' a unica transicao que evento nao dispara
     (§22.7.7 S1). O teste crava `hoje` (o motor de compliance opera em DATAS) e exige que a competencia
     cujo prazo ja' passou tenha sido movida PELO sweep — nao materializada 'vencida' de saida.
  3. IDEMPOTENCIA de verdade. Rodar duas vezes tem de dar o MESMO placar. A armadilha concreta e' o
     `objeto_id`: se fosse `random-uuid`, a chave UNIQUE ente⋈template⋈objeto_tipo⋈objeto_id nunca
     colidiria e cada corrida dobraria o painel. Este teste reprova essa regressao.

  `with-sistema` reusada de `oplenario.demo.casa-test` (mesmo padrao de `acervo_test.clj`/`sessoes_test.clj`)."
  (:require [casa]
            [clojure.test :refer [deftest is testing]]
            [compliance :as compliance-demo]
            [oplenario.demo.casa-test :refer [with-sistema]])
  (:import (java.time LocalDate)))

;; 12/09/2026: 01..06/2026 remetidas (prazos 28/02..30/07, todos passados) -> cumpridas;
;; 07/2026 venceu em 30/08 sem remessa -> o sweep a vence; 08/2026 vence em 30/09 -> pendente no prazo.
(def ^:private hoje (LocalDate/of 2026 9 12))

(deftest placar-sai-do-runtime-nao-da-semente
  (with-sistema [s]
    (let [{:keys [ente]} (casa/semear! s)
          {:keys [obrigacoes resumo vencidas-pelo-sweep]} (compliance-demo/semear! s ente hoje)
          por-competencia (into {} (map (juxt :competencia identity)) obrigacoes)]

      (testing "uma obrigação materializada por competência da narrativa"
        (is (= 8 (count obrigacoes)))
        (is (= #{"2026-01" "2026-02" "2026-03" "2026-04" "2026-05" "2026-06" "2026-07" "2026-08"}
               (set (keys por-competencia)))))

      (testing "o veredito veio do fato `remessa_enviada`, não de um estado escrito à mão"
        (doseq [c ["2026-01" "2026-02" "2026-03" "2026-04" "2026-05" "2026-06"]]
          (is (= "conforme" (:veredito (por-competencia c)))
              (str c ": remessa aceita -> o fato é verdadeiro -> conforme")))
        (doseq [c ["2026-07" "2026-08"]]
          (is (= "nao_conforme" (:veredito (por-competencia c)))
              (str c ": sem remessa aceita -> o fato é falso -> não conforme"))))

      (testing "o prazo veio de motor.prazo_dominio_vigente (dia 30 do mês seguinte, fev clampado)"
        (is (= (LocalDate/of 2026 2 28) (:vence-em (por-competencia "2026-01")))
            "fevereiro não tem dia 30 — clampa no último dia, não estoura")
        (is (= (LocalDate/of 2026 8 30) (:vence-em (por-competencia "2026-07"))))
        (is (= (LocalDate/of 2026 9 30) (:vence-em (por-competencia "2026-08")))))

      (testing "o vencimento veio do SWEEP, não da materialização"
        ;; a avaliação de 07/2026 roda em 01/08 (fim da competência), quando o prazo 30/08 ainda não
        ;; passou — nasce PENDENTE. Materializá-la com `hoje` a criaria já 'vencida' e o sweep, que é a
        ;; única transição que evento não dispara (§22.7.7 S1), nunca teria o que mover.
        (is (= "pendente" (:estado (por-competencia "2026-07")))
            "materializa PENDENTE — o estado devolvido é o de antes do sweep")
        (is (= "pendente" (:estado (por-competencia "2026-08")))
            "dentro do prazo em 01/09 e ainda em 12/09")
        (is (= 1 vencidas-pelo-sweep)
            "só 07/2026 (venceu 30/08); 08/2026 vence 30/09 e o sweep não a toca"))

      (testing "o card da Mesa: EM DIA 6 · PENDENTES 1 · VENCIDAS 1"
        (is (= 6 (get resumo "cumprida" 0)))
        (is (= 1 (get resumo "pendente" 0)))
        (is (= 1 (get resumo "vencida" 0)))))))

(deftest re-semear-nao-dobra-o-painel
  (with-sistema [s]
    (let [{:keys [ente]} (casa/semear! s)
          primeira (compliance-demo/semear! s ente hoje)
          segunda  (compliance-demo/semear! s ente hoje)]
      (is (= (:resumo primeira) (:resumo segunda))
          "objeto_id derivado da competência (não random-uuid) — a UNIQUE de materialização colide e a segunda corrida relê")
      (is (= 8 (count (:obrigacoes segunda)))))))
