(ns oplenario.paineis.situacao-test
  "UNIT (puro) — a fonte UNICA de derivacao de situacao de sessao (paineis.logic.situacao), compartilhada
  pelos adapters/out do SLI e do dashboard da Mesa (review clojure MEDIUM — elimina a divergencia de dois
  `case` paralelos)."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.paineis.logic.situacao :as situacao]))

(deftest derivar-mapeia-os-estados-conhecidos
  (is (= "em_curso" (situacao/derivar "aberta")))
  (is (= "suspensa" (situacao/derivar "suspensa")))
  (is (= "realizada" (situacao/derivar "encerrada")))
  (is (= "realizada" (situacao/derivar "arquivada")) "'arquivada' e' pos-encerrada -> realizada")
  (is (= "nao_realizada" (situacao/derivar "nao_realizada")))
  (is (= "agendada" (situacao/derivar "agendada"))))

(deftest derivar-passa-estado-desconhecido-cru
  (is (= "estado_novo_da_fonte" (situacao/derivar "estado_novo_da_fonte"))
      "enum ABERTO — a fonte (sessoes) pode ganhar estados novos sem quebrar o painel"))

(deftest ordenar-e-deterministico-e-canonico
  (is (= ["em_curso" "suspensa" "agendada" "realizada" "nao_realizada"]
         (situacao/ordenar ["nao_realizada" "realizada" "agendada" "suspensa" "em_curso"]))
      "ordem canonica ('o que acontece agora' primeiro), independente da ordem de entrada")
  (is (= ["em_curso" "zzz_desconhecida"] (situacao/ordenar ["zzz_desconhecida" "em_curso"]))
      "situacao desconhecida cai ao fim")
  (is (= [] (situacao/ordenar []))))
