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

(deftest a-cidada-tem-3-acompanhamentos-reais
  ;; Achado da verificacao AO VIVO (Daouda, 12/09/2026): a cidada alcancava a superficie autenticada
  ;; (GET /portal/acompanhamentos, 200) mas a lista vinha VAZIA — nenhuma das 4 sementes narrativas
  ;; criava acompanhamento nenhum. `transparencia.acompanhamento` e' TABELA DE DOMINIO (a coluna do dono
  ;; e' `seguidor_identidade_id`, NAO `identidade_id` — o briefing original errou essa coluna).
  ;;
  ;; CORRIGIDO DUAS VEZES (Daouda, 12/09/2026):
  ;;
  ;; (1) a 1a versao lia `:acompanhamentos` do retorno de `participacao-demo/semear!` sem esperar a
  ;; projecao — `meus-acompanhamentos` (RepoTransparencia) faz LEFT JOIN contra `transparencia.materia`,
  ;; MATERIALIZADA pelo relay assincrono. Reprovava com `Actual: #{nil}` — nao ementa errada, nil nas 3.
  ;;
  ;; (2) a CORRECAO da (1) foi esperar a projecao com poll bounded (mesma forma de outbox_test.clj) — mas
  ;; medido contra a SUITE CHEIA (nao so' isolado): outro teste da suite APAGA `transparencia.materia`
  ;; pro ente da demo (ficava com ZERO linhas enquanto outros entes tinham centenas), e os eventos que
  ;; produziriam a re-projecao JA' foram consumidos (`processed_at` preenchido na 1a drenagem, muito antes
  ;; deste teste rodar) — nao ha' evento pra' drenar, entao a projecao e' IRRECUPERAVEL por espera na
  ;; suite cheia. Aumentar o timeout so' faz reprovar mais devagar (a armadilha do instrumento: um timeout
  ;; aponta onde o relogio acabou, nunca onde o tempo foi gasto). `demo.*` afirma a forma de uma Casa
  ;; COMPARTILHADA e MUTAVEL que a propria suite danifica — conflito estrutural ja' documentado neste
  ;; projeto; um teste que reprova por acaso (verde isolado, vermelho na suite) TREINA a ignorar vermelho,
  ;; a forma espelhada do defeito que esta branch inteira combate. Por isso NENHUMA espera sobrevive aqui.
  ;;
  ;; A CORRECAO REAL (esta versao): afirmar QUEM atraves do que a projecao NAO PODE apagar.
  ;; `meus-acompanhamentos` devolve `:proposicao-id` SEMPRE (a subscricao, VERDADE de dominio, nunca
  ;; falta — so' o CABECALHO pode faltar, com `:indisponivel true`, db/acompanhamento.clj/meus-da-materia).
  ;; Entao:
  ;;   - identidade: o CONJUNTO de `:proposicao-id` que a rota devolve == o conjunto que a semente
  ;;     escolheu (`proposicoes-para-acompanhar`, chamada aqui de novo — a MESMA leitura contra a tabela
  ;;     DONA `legislativo.proposicoes`, nao redigitada) — prova QUEM, imune ao apagamento da projecao.
  ;;   - o QUE essas materias SAO (ementa/estado, em portugues): afirmado contra a tabela DONA, nunca
  ;;     contra o read-model projetado.
  ;;   - o contrato do LEFT JOIN vira asserção PROPRIA: cabecalho ausente NUNCA derruba o item (a
  ;;     subscricao aparece sempre) e vem marcado `:indisponivel true` de forma CONSISTENTE com `:ementa`
  ;;     nil — trava a regressao da frente "truncamento-familia" que hoje nada trava.
  (with-sistema [s]
    (let [{:keys [ente identidades]} (casa/semear! s)
          cidadao-id (:cidadao identidades)
          r (participacao-demo/semear! s ente)
          repo-legislativo (:repo-legislativo s)
          ;; a MESMA resolucao que `semear-acompanhamentos!` usou pra escolher as 3 — reusada, nao
          ;; redigitada (a fn e' privada de proposito; `#'` e' o jeito Clojure de reusar sem promove-la a
          ;; API publica so' pro teste). Contra `legislativo.proposicoes`, a tabela DONA — nunca a projecao.
          escolhidas (#'participacao-demo/proposicoes-para-acompanhar repo-legislativo ente)
          {:keys [acompanhamentos acompanhamentos-total]} (:acompanhamentos r)]
      (testing "3 acompanhamentos ATIVOS — nao 0"
        (is (= 3 acompanhamentos-total))
        (is (= 3 (count acompanhamentos))))
      (testing "QUEM ela segue — o conjunto de proposicao-id bate com o que a semente escolheu (IMUNE ao apagamento da projecao: a subscricao e' VERDADE de dominio, so' o cabecalho projetado pode faltar)"
        (is (= (set (map :id escolhidas)) (set (map :proposicao-id acompanhamentos)))))
      (testing "O QUE sao essas 3 materias, em portugues — contra a tabela DONA (legislativo.proposicoes), NUNCA o read-model projetado"
        (is (= #{"Altera a Lei Orgânica do Município quanto à composição da Mesa Diretora."
                 "Manifesta congratulações à comunidade escolar pela conquista na Olimpíada Municipal de Matemática."
                 "Dispõe sobre a acessibilidade em prédios públicos municipais."}
               (set (map :ementa escolhidas)))))
      (testing "contrato do LEFT JOIN (frente 'truncamento-familia'): cabecalho ausente NUNCA derruba o item, e vem marcado :indisponivel true de forma consistente com :ementa nil"
        (doseq [item acompanhamentos]
          (is (some? (:proposicao-id item)) "a subscricao em si nunca falta, seja qual for o estado da projecao")
          (is (= (nil? (:ementa item)) (boolean (:indisponivel item)))
              (str "ementa nil <=> :indisponivel true, NUNCA divergem — item " (:proposicao-id item)))))
      (testing "pelo menos 1 das 3 e' NAO-terminal — o acompanhamento tem FUTURO (o fan-out de notificacao so' reage a proposicao.transicionou; 'aprovada'/'arquivada' nunca mais transicionam)"
        (is (some #(not (contains? #{"aprovada" "arquivada"} (:estado %))) escolhidas)))
      (testing "reexecutar semear! nao duplica (seguir! e' UPSERT por ente,proposicao,seguidor)"
        (participacao-demo/semear! s ente)
        (is (= 3 (:acompanhamentos-total (:acompanhamentos (participacao-demo/semear! s ente)))))))))
