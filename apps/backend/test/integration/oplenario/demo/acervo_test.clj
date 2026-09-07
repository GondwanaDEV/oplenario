(ns oplenario.demo.acervo-test
  "INTEGRACAO (PG real): `acervo/semear!` (plano `docs/superpowers/plans/2026-09-07-prontidao-de-
  apresentacao.md`, Task 0.4) — 24 proposicoes cobrindo os 6 estados de um RITO REAL da Casa (dado,
  Invariante 4), 3 pareceres, 2 autografos e 4 normas. TESTE LITERAL do Passo 1 da Task 0.4 (plano
  L317-333) — ancorado nas DUAS autoridades reais: `legislativo.logic/tipos` (vocabulario de `tipo`,
  `src/oplenario/legislativo/logic.clj:13`) e o proprio `legislativo.template_estado` que este ns
  instala (Invariante 4: os estados do rito sao DADO, `legislativo.proposicoes.estado` NAO TEM CHECK —
  migration 20260620000013:29, comentario 'coarse; a maquina fina e' a tramitacao'). `with-sistema`
  reusada de `oplenario.demo.casa-test` (carry #1 do briefing: nao existe em nenhum outro lugar do repo)."
  (:require [acervo]
            [casa]
            [clojure.set]
            [clojure.test :refer [deftest is testing]]
            [oplenario.demo.casa-test :refer [with-sistema]]
            [oplenario.legislativo.logic :as legislativo.logic]))

(deftest acervo-usa-vocabulario-real-e-cobre-o-rito-que-instala
  (with-sistema [s]
    (let [{:keys [ente]} (casa/semear! s)
          {:keys [template-id]} (acervo/semear! s ente)
          por-estado (acervo/contar-por-estado s ente)
          estados-do-template (acervo/estados-do-template s ente template-id)]
      (testing "todo tipo usado existe em legislativo.logic/tipos — a autoridade real"
        (is (empty? (clojure.set/difference (acervo/tipos-usados s ente)
                                             legislativo.logic/tipos))))
      (testing "todo estado que o template DECLARA tem pelo menos uma matéria nele"
        (is (seq estados-do-template) "template sem estado não prova nada")
        (doseq [estado estados-do-template]
          (is (pos? (get por-estado estado 0))
              (str "o rito declara '" estado "' e nenhuma matéria está nele"))))
      (testing "nenhuma matéria em estado que o template não declara"
        (is (empty? (clojure.set/difference (set (keys por-estado))
                                             (set estados-do-template))))))))
