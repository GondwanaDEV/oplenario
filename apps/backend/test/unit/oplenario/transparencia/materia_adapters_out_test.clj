(ns oplenario.transparencia.materia-adapters-out-test
  "UNIT (puro, sem DB) — achado IMPORTANTE da revisao adversarial da frente 'truncamento-familia': o gate
  `materias->wire` tinha `(or materias-total 0)`, que desarmava a UNICA trava automatica do par lista+total.
  `MateriasOut` e' `{:closed true}` com `:materias-total :int` — se o Repo um dia deixar de devolver a
  chave (renomear, bug de projecao), o schema DEVERIA reprovar ALTO (500, bug de servidor); com o `or`, `nil`
  virava `0` silencioso, o contrato passava, e a borda nunca via o aviso de corte — o UNICO modo de falha que
  esta fatia inteira existe para matar."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.transparencia.adapters.out.materia :as adapters]))

(deftest materias-total-ausente-lanca-nao-vira-zero-silencioso
  (is (thrown? clojure.lang.ExceptionInfo (adapters/materias->wire {:materias []}))
      "sem :materias-total no mapa de dominio, o adapter tem de lancar (bug de servidor) — nao coagir a 0"))
