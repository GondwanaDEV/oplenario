(ns oplenario.sessoes.listagem-ordenacao-test
  "Unit (PURO): `logic/chave-ordenacao-listagem-geral` — a chave de ordenacao de GET /sessoes (ledger de
  prontidao #16). Sem I/O, sem HTTP — so a funcao pura que decide a ordem: aberta/suspensa primeiro
  (grupo 'agora'), agendadas por data crescente (grupo 'futuro'), fechadas por data decrescente (grupo
  'passado'). O teste de integracao HTTP (`sessoes-listagem-http-in-test`) prova a MESMA regra fim a fim;
  este ns prova a MECANICA da chave isoladamente, inclusive os casos de borda (marco ausente, estado fora
  do vocabulario) que seriam caros de montar via fixture HTTP."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.sessoes.logic :as logic])
  (:import (java.time Instant)))

(def ^:private t0 (Instant/parse "2026-06-30T13:00:00Z"))
(defn- mais [^Instant t s] (.plusSeconds t s))

(defn- ordenar [sessoes]
  (mapv :id (sort-by logic/chave-ordenacao-listagem-geral sessoes)))

(deftest aberta-vem-antes-de-agendada-e-de-fechada
  (let [a {:id "aberta" :estado "aberta" :aberta-em t0}
        g {:id "agendada" :estado "agendada" :agendada-para t0}
        f {:id "fechada" :estado "encerrada" :encerrada-em t0}]
    (is (= ["aberta" "agendada" "fechada"] (ordenar [f g a])))))

(deftest suspensa-fica-no-mesmo-grupo-de-aberta
  (let [s {:id "suspensa" :estado "suspensa" :aberta-em t0}
        g {:id "agendada" :estado "agendada" :agendada-para t0}]
    (is (= ["suspensa" "agendada"] (ordenar [g s]))
        "suspensa (recesso) e' 'agora', nao 'futuro' -- nao fecha a sessao")))

(deftest agendadas-ordenam-por-data-crescente
  (let [longe {:id "longe" :estado "agendada" :agendada-para (mais t0 (* 10 86400))}
        perto {:id "perto" :estado "agendada" :agendada-para (mais t0 86400)}]
    (is (= ["perto" "longe"] (ordenar [longe perto])))))

(deftest fechadas-ordenam-por-data-decrescente
  (let [antiga {:id "antiga" :estado "encerrada" :encerrada-em t0}
        recente {:id "recente" :estado "encerrada" :encerrada-em (mais t0 3600)}]
    (is (= ["recente" "antiga"] (ordenar [antiga recente]))
        "a mais recente primeiro -- decrescente, ao contrario dos outros dois grupos")))

(deftest fechada-usa-coalesce-encerrada-agendada-aberta
  (let [nao-realizada {:id "nr" :estado "nao_realizada" :agendada-para t0}
        arquivada-sem-marco {:id "arq" :estado "arquivada" :aberta-em (mais t0 100)}]
    (is (= ["arq" "nr"] (ordenar [nao-realizada arquivada-sem-marco]))
        "sem encerrada-em, cai no marco seguinte do COALESCE (agendada-para, depois aberta-em)")))

(deftest marco-ausente-vai-para-o-fim-do-proprio-grupo-nunca-lanca
  (let [com-data {:id "com-data" :estado "agendada" :agendada-para t0}
        sem-data {:id "sem-data" :estado "agendada" :agendada-para nil}]
    (is (= ["com-data" "sem-data"] (ordenar [sem-data com-data]))
        "agendada sem data (agendada->nao_realizada sem marco) nao lanca -- so' vai para o fim do grupo"))
  (let [com-data {:id "com-data" :estado "encerrada" :encerrada-em t0}
        sem-data {:id "sem-data" :estado "encerrada" :encerrada-em nil :agendada-para nil :aberta-em nil}]
    (is (= ["com-data" "sem-data"] (ordenar [sem-data com-data]))
        "fechada sem NENHUM marco (defensivo) tambem vai para o fim, nunca lanca")))

(deftest estado-fora-do-vocabulario-nunca-lanca-vai-para-o-ultimo-grupo
  ;; defensivo: `estados-sessao` e' fechado por CHECK (mig 0026) -- este caso nao deveria ser alcancavel
  ;; pela API real. A leitura tem de ser fail-SAFE (nunca lancar so' por ordenar), nao fail-closed --
  ;; quem barra estado invalido e' a ESCRITA, nunca esta leitura.
  (let [aberta {:id "aberta" :estado "aberta" :aberta-em t0}
        corrompida {:id "corrompida" :estado "estado-inexistente"}]
    (is (= ["aberta" "corrompida"] (ordenar [corrompida aberta])))))

(deftest desempate-final-por-id-e-estavel
  ;; duas sessoes do MESMO grupo com o MESMO carimbo nao podem "dancar" de posicao entre chamadas --
  ;; o desempate final por id (string, ordem lexica) garante uma unica ordem possivel.
  (let [x {:id "b-depois" :estado "aberta" :aberta-em t0}
        y {:id "a-antes" :estado "aberta" :aberta-em t0}]
    (is (= ["a-antes" "b-depois"] (ordenar [x y])))))
