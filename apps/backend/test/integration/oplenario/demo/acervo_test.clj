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
            [oplenario.cadastros.components.repositorio :as repo-cadastros]
            [oplenario.demo.casa-test :refer [with-sistema]]
            [oplenario.legislativo.components.repositorio :as repo-legislativo]
            [oplenario.legislativo.logic :as legislativo.logic]))

(deftest acervo-usa-vocabulario-real-e-cobre-o-rito-que-instala
  (with-sistema [s]
    (let [{:keys [ente identidades]} (casa/semear! s)
          {:keys [template-id]} (acervo/semear! s ente (:vereador identidades))
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

(deftest o-vereador-da-identidade-e-relator-de-parecer-assinavel
  ;; Ledger #12 (docs/16-ledger-prontidao.md): a identidade `:vereador` da demo NUNCA era relatora de
  ;; parecer nenhum (`semear-pareceres!` designava relator entre os 3 primeiros do roster, sem vinculo
  ;; com nenhuma identidade — `vereador/listar` nem é chamado por identidade) — GET
  ;; /parecer/:id/assinar respondia 404 "parecer não encontrado" pro login vereador, e a jornada J3
  ;; (o parecer) não podia ser demonstrada de ponta a ponta.
  (with-sistema [s]
    (let [{:keys [ente identidades]} (casa/semear! s)
          vereador-identidade (:vereador identidades)
          _ (acervo/semear! s ente vereador-identidade)
          repo-cad (:repo-cadastros s)
          repo-leg (:repo-legislativo s)
          vereador-id (:id (repo-cadastros/vereador-por-identidade repo-cad ente vereador-identidade))
          pareceres (:pareceres (repo-legislativo/meu-painel repo-leg ente vereador-id))]
      (testing "o vereador da identidade :vereador e' relator de pelo menos 1 parecer"
        (is (seq pareceres)
            "identidade :vereador nunca e' relatora — GET /parecer/:id/assinar responde 404"))
      (testing "existe parecer NAO terminal (assinavel) entre eles"
        (is (some #(not (contains? legislativo.logic/estados-parecer-terminais (:estado %))) pareceres)
            "todo parecer do relator ja' esta' num dos 4 estados terminais — nao ha' o que assinar")))))

(deftest comissao-id-do-parecer-aponta-para-comissao-real-da-casa
  ;; Ledger #11 (docs/16-ledger-prontidao.md): `semear-pareceres!` gravava `comissao-id` como
  ;; `(random-uuid)` — guard ref ORFAO (sem FK, §22.10) — e a tela /parecer/:id mostrava esse UUID
  ;; cru onde deveria ir o nome da comissao.
  (with-sistema [s]
    (let [{:keys [ente identidades]} (casa/semear! s)
          _ (acervo/semear! s ente (:vereador identidades))
          ids-comissoes-reais (set (map :id (acervo/comissoes s ente)))
          ids-comissoes-dos-pareceres (set (map :comissao-id (acervo/pareceres s ente)))]
      (testing "nenhum parecer aponta pra comissao inexistente"
        (is (seq ids-comissoes-dos-pareceres) "nenhum parecer achado — a semente rodou?")
        (is (empty? (clojure.set/difference ids-comissoes-dos-pareceres ids-comissoes-reais))
            "comissao-id do parecer nao bate com nenhuma comissao real da Casa — guard ref orfao")))))
