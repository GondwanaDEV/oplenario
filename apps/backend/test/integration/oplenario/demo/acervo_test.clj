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

;; A3 (revisao adversarial de conserta-3-mata, #14): a inscricao das 24 proposicoes no Livro do Protocolo
;; Geral (acervo.clj/protocolar-e-tramitar!, repo-leg/protocolar-geral!) so' tinha sido verificada AO VIVO
;; pelo revisor — nenhum teste provava. REPROVA se `protocolar-e-tramitar!` parar de chamar
;; `protocolar-geral!` (Livro vazio), se o `objeto-tipo`/`sentido` regredirem, ou se a numeracao deixar de
;; ser gapless 1..24 (ex.: um crash no meio do loop, ou dois entes compartilhando o mesmo escopo por
;; engano).
(deftest acervo-inscreve-as-24-proposicoes-no-livro-do-protocolo-geral
  (with-sistema [s]
    (let [{:keys [ente identidades]} (casa/semear! s)
          _ (acervo/semear! s ente (:vereador identidades))
          repo-leg (:repo-legislativo s)
          livro-2026 (repo-legislativo/protocolos-do-ano repo-leg ente 2026)
          proposicoes (acervo/tipos-usados s ente)
          entradas-de-proposicao (filterv #(= "proposicao" (:objeto-tipo %)) livro-2026)]
      (testing "as 24 proposicoes da semente estao TODAS inscritas no Livro"
        (is (= 24 (count entradas-de-proposicao))
            "o Livro nao tem as 24 entradas 'proposicao' esperadas — protocolar-geral! parou de ser chamado?"))
      (testing "sentido 'interno' — vereador da PROPRIA Casa, nunca 'recebido' (externo)"
        (is (every? #(= "interno" (:sentido %)) entradas-de-proposicao)
            "alguma entrada regrediu p/ sentido diferente de 'interno'"))
      (testing "numeracao gapless 1..24, sem furo (append-only, escopo protocolo_geral:2026 do ente)"
        (is (= (range 1 25) (sort (mapv :numero entradas-de-proposicao)))
            "numeracao nao e' 1..24 gapless — sinal de crash-no-meio-do-loop ou escopo de sequencial compartilhado"))
      (testing "cada entrada aponta pra uma proposicao real (objeto-id existe entre as protocoladas)"
        (is (seq proposicoes) "acervo/tipos-usados vazio — a semente rodou?")
        (is (every? some? (map :objeto-id entradas-de-proposicao))
            "entrada 'proposicao' sem objeto-id — guard ref orfao no Livro")))))

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
