(ns oplenario.demo.sessoes-test
  "INTEGRACAO (PG real): `sessoes/semear!` (plano `docs/superpowers/plans/2026-09-07-prontidao-de-
  apresentacao.md`, Task 0.5, fundida com a antiga Task 0.3) — as TRES sessoes da demo: ENCERRADA (chamada
  + 2 votacoes apuradas + tribuna com fala encerrada), ABERTA (quorum real + votacao nominal aberta +
  tribuna com fala em curso) e AGENDADA (pauta montada, zero presenca). TESTE LITERAL do Passo 1 da Task
  0.5 (plano L364-381), com DUAS correcoes contra a fonte:

  1. VOCABULARIO — a redacao original do plano usava 'em_curso' p/ a sessao ao vivo; o CHECK real
     (migration `20260620000026-sessoes-sessao.up.sql:19-20`) tem exatamente
     agendada·aberta·suspensa·encerrada·nao_realizada·arquivada — a sessao ao vivo e' 'aberta'. Corrigido
     no proprio plano em 07/09 antes desta task rodar.
  2. A GARANTIA CENTRAL (herdada da Task 0.3, fundida aqui, §1.2/`Registro do defeito original` do plano):
     o defeito mais caro ja' medido no projeto era 14 presencas de pessoas FORA do cadastro (o telao
     mostrava UUID sem nome) enquanto os 17 vereadores nominados tinham ZERO presenca. A asercao
     'nenhuma presenca de pessoa fora do cadastro' abaixo NAO esta' no teste literal do plano — foi
     ACRESCENTADA (briefing desta sessao) porque e' exatamente essa a garantia que a fusao da Task 0.3
     exige provar, e sem ela `sessoes/semear!` podia regredir para `random-uuid` sem nenhum teste vermelho.

  `with-sistema` reusada de `oplenario.demo.casa-test` (carry #1 do briefing — nao existe em nenhum outro
  lugar do repo, mesmo padrao de `acervo_test.clj`). DEPENDE do acervo (`acervo/semear!`, Task 0.4 — 3
  proposicoes 'em_pauta' + 4 'aguardando_pauta' reais).

  AUTO-SUFICIENTE (15/09/2026): este teste passou a semear o acervo ELE MESMO, logo apos a Casa. Antes
  ele dependia de `acervo_test` ter rodado antes contra o MESMO banco — premissa de ORDEM que o runner
  nao garante: no 1o CI que rodou a suite inteira, este teste morreu com `precisa de >=3 proposicoes
  'em_pauta' … rode acervo/semear! primeiro` porque o acervo nao estava semeado na hora. `acervo/semear!`
  e' idempotente (relê em vez de duplicar), entao semea-lo aqui e' seguro mesmo quando `acervo_test` ja'
  rodou. `materias-em-pauta` (sessoes.clj:135) lê a tabela DONA `legislativo.proposicoes` via
  `listar-e-contar-proposicoes` — nao a projecao que outro teste da suite apaga — logo semear o acervo
  basta."
  (:require [acervo]
            [casa]
            [clojure.set]
            [clojure.test :refer [deftest is testing]]
            [oplenario.demo.casa-test :refer [with-sistema]]
            [sessoes :as sessoes-demo]))

(deftest tres-sessoes-em-estados-distintos
  (with-sistema [s]
    (let [{:keys [ente vereadores identidades]} (casa/semear! s)
          ids-do-roster (set (map :id vereadores))
          _ (acervo/semear! s ente (:vereador identidades))
          {:keys [encerrada aberta agendada]} (sessoes-demo/semear! s ente)]
      (testing "os três estados são do CHECK da migration, não inventados"
        (is (= "encerrada" (:estado (sessoes-demo/buscar s ente encerrada))))
        (is (= "aberta"    (:estado (sessoes-demo/buscar s ente aberta))))
        (is (= "agendada"  (:estado (sessoes-demo/buscar s ente agendada)))))
      (testing "a encerrada tem chamada registrada e votação apurada"
        (is (pos? (sessoes-demo/votos-apurados s ente encerrada))))
      (testing "a aberta tem quórum de gente do roster e votação em aberto"
        (is (>= (sessoes-demo/quorum s ente aberta) 9))
        (is (some? (sessoes-demo/votacao-aberta s ente aberta))
            "sem votação aberta o vereador não tem o que votar ao vivo"))
      (testing "a aberta TAMBEM tem pauta montada — ledger #6"
        ;; Ledger #6 (docs/16-ledger-prontidao.md): os 5 itens de pauta iam SO' pra sessao 'agendada';
        ;; a 'aberta' (o telao AO VIVO, /sessoes/:id/plenario) tinha 0 itens, e a regiao central do
        ;; telao mostrava "Nenhum item ativo na pauta ainda" durante a sessao em curso.
        (is (pos? (sessoes-demo/itens-de-pauta s ente aberta))
            "telao da sessao AO VIVO sem pauta — 'Nenhum item ativo na pauta ainda' no meio da sessao"))
      (testing "a agendada tem pauta montada e nenhuma presença"
        (is (pos? (sessoes-demo/itens-de-pauta s ente agendada)))
        (is (zero? (sessoes-demo/quorum s ente agendada))))
      (testing "nenhuma presença de pessoa fora do cadastro"
        ;; O DEFEITO ORIGINAL (Task 0.3, fundida aqui): 14 presenças de pessoas que o cadastro não conhece
        ;; — o telão do plenário mostraria UUID sem nome. Checa as DUAS sessões com presença real
        ;; (encerrada tem chamada; aberta tem o quórum ao vivo) contra o roster de `casa/semear!`.
        (let [ids-com-presenca (->> [encerrada aberta]
                                     (mapcat #(sessoes-demo/presencas s ente %))
                                     (map :vereador-id)
                                     set)]
          (is (seq ids-com-presenca) "nenhuma presença foi semeada — a asserção abaixo não provaria nada")
          (is (empty? (clojure.set/difference ids-com-presenca ids-do-roster))
              "presença fantasma: o telão mostraria UUID sem nome"))))))
