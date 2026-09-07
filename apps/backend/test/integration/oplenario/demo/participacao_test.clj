(ns oplenario.demo.participacao-test
  "INTEGRACAO (PG real): `participacao/semear!` (plano `docs/superpowers/plans/2026-09-07-prontidao-de-
  apresentacao.md`, Task 0.6) — 3 pedidos e-SIC (aberto no prazo, respondido, respondido+recurso aberto),
  2 solicitacoes LGPD, 2 manifestacoes de ouvidoria, 5 comentarios (3 aprovados, 2 na fila de moderacao) e
  1 Encarregado/DPO.

  TESTE LITERAL do Passo 1 da Task 0.6 (plano L411-419), com UMA correcao contra a fonte: a asserção
  original do plano usava `#{\"aberto\" \"respondido\" \"em_recurso\"}` como vocabulario de
  `pedido_esic.estado` — dois dos tres rotulos NAO sao estados de banco. O CHECK real (migration
  `20260620000039-participacao-esic-pedido.up.sql:29-30`, espelhado em `participacao.logic/estados-pedido`,
  `src/oplenario/participacao/logic.clj:17`) tem EXATAMENTE `protocolado · em_analise · respondido ·
  indeferido`, com trigger `imut_trava_estado_terminal` travando o terminal em `respondido`/`indeferido`
  (mesma migration:69). 'Aberto no prazo' e' um pedido em `protocolado` (o default, ainda dentro do
  relogio LAI); 'em recurso' NAO e' um 4o estado do pedido — e' um pedido `respondido` com um `recurso_esic`
  ENTIDADE SEPARADA (migration `20260620000040-participacao-esic-recurso-resposta.up.sql`) aberto
  (`protocolado`, NAO decidido) pendurado nele. Corrigido abaixo para o vocabulario real, com a distincao
  provada por um campo separado (`:recurso`), nao por um 4o valor de `:estado` que a fonte nao tem.

  ATENCAO A DIVERGENCIA DO BRIEFING DESTA SESSAO: o briefing recebido citava 'recurso `estado` ∈
  pendente·cumprida·vencida·dispensada·cancelada' — esse e', na verdade, o vocabulario de
  `participacao.prazo_ativo.estado` (mesma migration 39:81-82), NAO de `recurso_esic.estado` (que e' so'
  `protocolado`/`decidido`, migration 20260620000040:149, `participacao.logic/estados-recurso` linha 47).
  Confirmado contra a fonte — o briefing conflou as duas tabelas.

  `with-sistema` reusada de `oplenario.demo.casa-test` (carry #1 do briefing — nao existe em nenhum outro
  lugar do repo, mesmo padrao de `acervo_test.clj`/`sessoes_test.clj`). DEPENDE do ACERVO
  (`acervo/semear!`) ja' ter rodado contra este banco: os 5 comentarios apontam para proposicoes REAIS do
  acervo, lidas via `repo-legislativo/listar-e-contar-proposicoes` (a MESMA leitura paginada do FE, nenhum
  SELECT cru) — este teste, como `sessoes_test.clj`, nao semeia o acervo, so a Casa."
  (:require [casa]
            [clojure.test :refer [deftest is testing]]
            [oplenario.demo.casa-test :refer [with-sistema]]
            [participacao :as participacao-demo]))

(deftest participacao-tem-exemplar-de-cada-estado
  (with-sistema [s]
    (let [{:keys [ente]} (casa/semear! s)
          r (participacao-demo/semear! s ente)]
      (testing "3 pedidos e-SIC: aberto no prazo, respondido, e respondido com recurso aberto"
        (is (= 3 (count (:esic r))))
        (is (= #{"protocolado" "respondido"} (set (map :estado (:esic r))))
            "vocabulario real do CHECK (migration 20260620000039:29-30) — 'aberto'/'em_recurso' do plano são rótulos de UI, não estados de banco")
        (is (= 1 (count (filter :recurso (:esic r))))
            "exatamente 1 pedido tem um recurso interposto e AINDA não decidido")
        (is (= "protocolado" (:estado (:recurso (first (filter :recurso (:esic r))))))
            "o recurso deste pedido está aberto (não decidido) — é isso que a jornada 'em recurso' mostra"))
      (testing "2 solicitações LGPD"
        (is (= 2 (count (:lgpd r)))))
      (testing "2 manifestações de ouvidoria"
        (is (= 2 (count (:ouvidoria r)))))
      (testing "5 comentários em matérias do acervo, 3 aprovados"
        (is (= 5 (count (:comentarios r))))
        (is (= 3 (count (filter #(= "aprovado" (:estado %)) (:comentarios r))))))
      (testing "a fila de moderação NÃO pode estar vazia — é o que se mostra ao servidor"
        (is (= 2 (count (:moderacao-pendente r)))))
      (testing "1 Encarregado/DPO"
        (is (some? (:encarregado r)))
        (is (some? (:email (:encarregado r))))))))
