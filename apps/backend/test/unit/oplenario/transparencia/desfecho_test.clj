(ns oplenario.transparencia.desfecho-test
  "Os atos depois do plenario em palavras (docs/16, retriagem linhas 18 e 30)."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.transparencia.logic.desfecho :as d]))

(deftest rotulos-dos-atos
  (is (= "Aprovada em plenário" (d/rotulo {:ato "aprovada"})))
  (is (= "Redação final aprovada em plenário" (d/rotulo {:ato "aprovada" :redacao-final true})))
  (is (= "Rejeitada em plenário" (d/rotulo {:ato "rejeitada"})))
  (is (= "Autógrafo nº 8/2026 enviado ao Executivo" (d/rotulo {:ato "autografo_enviado" :numero 8 :ano 2026})))
  (is (= "Autógrafo enviado ao Executivo" (d/rotulo {:ato "autografo_enviado"})))
  (is (= "Sancionada pelo Executivo" (d/rotulo {:ato "sancionado"})))
  (is (= "Vetada pelo Executivo" (d/rotulo {:ato "vetado"})))
  (is (= "Veto derrubado pela Câmara" (d/rotulo {:ato "veto_derrubado"})))
  (is (= "Promulgação: Lei nº 5/2026" (d/rotulo {:ato "promulgada" :tipo-norma "lei" :numero 5 :ano 2026})))
  (is (= "Publicação: Decreto Legislativo nº 2/2026"
         (d/rotulo {:ato "publicada" :tipo-norma "decreto_legislativo" :numero 2 :ano 2026})))
  (is (nil? (d/rotulo {:ato "ato_de_uma_versao_futura"})) "ato desconhecido nao ganha rotulo inventado"))

(deftest rotulos-dos-turnos
  ;; a emenda a Lei Organica vota em dois turnos (CF art. 29): o 1o turno aprovado NAO e' a materia aprovada
  (is (= "Aprovada em 1º turno" (d/rotulo {:ato "aprovada" :turno 1})))
  (is (= "Aprovada em 2º turno" (d/rotulo {:ato "aprovada" :turno 2})))
  (is (= "Rejeitada em 2º turno" (d/rotulo {:ato "rejeitada" :turno 2})))
  (is (= "ato:aprovada:turno_1" (d/chave {:ato "aprovada" :turno 1})))
  (is (not= (d/chave {:ato "aprovada" :turno 1}) (d/chave {:ato "aprovada" :turno 2}))
      "os dois turnos sao duas linhas na linha do tempo, nao uma"))

(deftest chave-nunca-colide-com-etapa-do-rito
  (is (= "ato:aprovada" (d/chave {:ato "aprovada"})))
  (is (= "ato:aprovada:redacao_final" (d/chave {:ato "aprovada" :redacao-final true})))
  (is (nil? (d/chave {:ato ""}))))
