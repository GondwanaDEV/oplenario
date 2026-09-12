(ns oplenario.demo.casa-test
  "INTEGRACAO (PG real): `casa/semear!` (plano `docs/superpowers/plans/2026-09-07-prontidao-de-
  apresentacao.md`, Task 0.2) produz UMA UNICA Casa, re-executavel.

  PREMISSA CORRIGIDA CONTRA O CODIGO (Passo 1/2 da Task 0.2): o plano localiza este arquivo em
  `apps/backend/test/oplenario/demo/casa_test.clj` e assume uma macro `with-sistema` ja existente.
  Nenhuma das duas e' verdade — `test/oplenario/...` NAO e' um test-path de nenhuma suite kaocha
  (`tests.edn` so registra `test/unit`, `test/integration`, `test/e2e`, `test/keycloak`; um arquivo fora
  dai nunca seria descoberto por `clojure -M:test --focus ...`, o comando exato que a Task 0.2 manda
  usar), e `grep -rn with-sistema` no repo inteiro nao acha NADA — nenhum helper compartilhado com esse
  nome. Este arquivo, por isso, (1) mora em `test/integration/oplenario/demo/` — o test-path real mais
  proximo do que o plano descreve, mantendo o MESMO nome de ns (`oplenario.demo.casa-test`, o que o
  `--focus` da Task 0.2 comanda) — e (2) define `with-sistema` localmente abaixo, no MESMO formato do
  fixture `:once` de `test/integration/oplenario/repo_test.clj`/`sistema_test.clj`
  (`component/start` do `oplenario.sistema/novo-sistema` + `migracao/migrar!`), que e' o unico padrao
  de boot de sistema completo ja usado no repo."
  (:require [casa]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [com.stuartsierra.component :as component]
            [oplenario.cadastros.components.repositorio :as repo-cadastros]
            [oplenario.config :as config]
            [oplenario.identidade.db.vinculo :as vinc]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.sistema :as sistema])
  (:import (java.time LocalDate)))

(defmacro with-sistema
  "Boota o sistema Component (datasource + os sub-systems dos modulos) p/ a duracao de `body`, migra o
  banco (idempotente — mesmo padrao de `repo_test.clj`) e para o sistema no `finally`. `sym` fica
  ligado ao sistema BOOTADO, reusavel por MULTIPLAS chamadas a `casa/semear!` dentro do mesmo `body` —
  e' o que a Task 0.2 precisa p/ provar que re-executar nao cria uma segunda Casa."
  [[sym] & body]
  `(let [~sym (component/start (sistema/novo-sistema (config/carregar)))]
     (try
       (migracao/migrar! (:ds (:datasource ~sym)))
       ~@body
       (finally (component/stop ~sym)))))

(deftest semear-produz-uma-unica-casa
  (with-sistema [s]
    (let [r1 (casa/semear! s)
          r2 (casa/semear! s)]
      (testing "re-executar não cria uma segunda Casa"
        (is (= (:ente r1) (:ente r2))))
      (testing "17 vereadores, todos com mandato vigente na data de hoje"
        (is (= 17 (count (:vereadores r1))))
        (is (every? :mandato-id (:vereadores r1))))
      (testing "nenhum nome duplicado no roster"
        (is (= 17 (count (distinct (map :nome (:vereadores r1)))))))
      (testing "o artefato de ids foi REALMENTE gravado, e nao só tentado"
        ;; A 1a redacao engolia a falha de escrita num `catch`+`log/warn`: sob o mount `:ro` do
        ;; container de teste a gravacao falhava e a funcao devolvia sucesso. O efeito so' apareceria
        ;; na sonda (Task 1.1), como "demo-ids.edn nao existe", longe da causa. Agora `gravar-artefato!`
        ;; falha alto, e quem legitimamente nao pode escrever no CWD — este teste — aponta
        ;; DEMO_ARTIFACTS_DIR para um diretorio gravavel. Sem esta assercao a regressao volta calada.
        ;; O caminho vem de `casa/diretorio-de-artefatos` — a MESMA regra que a producao usa. Redigitar
        ;; `(System/getenv "DEMO_ARTIFACTS_DIR")` aqui amarrava o teste a uma variavel que nenhum comando
        ;; de suite (nem o CI) seta: `(io/file nil "...")` estourava NPE e o teste reprovava por si.
        (let [alvo (io/file (casa/diretorio-de-artefatos) "demo-ids.edn")]
          (is (.exists alvo) (str "esperado o arquivo de ids em " (.getAbsolutePath alvo)))
          (let [lido (edn/read-string (slurp alvo))]
            (is (= (:ente r1) (:ente lido)) "o ente gravado tem de ser o ente semeado")
            (is (= 17 (count (:vereadores lido))))))))))

(deftest a-ficha-do-presidente-mostra-cargo-e-comissao-da-mesa
  ;; Ledger #3/#4 (docs/16-ledger-prontidao.md): `comissao_membro` da Mesa tinha 0 linhas enquanto os 4
  ;; cargos existiam em `comissao_cargo` com vereador ligado. `ficha-vereador` (repositorio.clj:136)
  ;; resolve `:comissoes` via `comissao/comissoes-do-vereador` (db/comissao.clj:54-71), que faz INNER
  ;; JOIN em `comissao_membro` — sem membro, a Mesa some da ficha, e a ficha do presidente mostrava
  ;; "Sem cargo na Mesa"/"Sem comissões atribuídas" enquanto a LISTA (`/cadastros/vereadores`, que le'
  ;; `comissao_cargo` direto via `cargo-mesa-lateral`, db/vereador.clj:276) e `/sessoes/:id/composicao`
  ;; (mesma leitura, via `roster-da-casa`) mostravam "PT · presidente" ao lado — contradicao visivel na
  ;; MESMA tela.
  ;;
  ;; Resolve o presidente pela IDENTIDADE (`vereador-por-identidade`), nao por indice do vetor
  ;; `:vereadores` — em re-execucao (`ja-semeada?` = true), `ler-cadastro` devolve os vereadores na
  ;; ordem de `vereador/listar` (por NOME, alfabetica), nao na ordem de `vereadores-base` — `(first
  ;; vereadores)` so' seria o presidente por coincidencia.
  (with-sistema [s]
    (let [{:keys [ente identidades]} (casa/semear! s)
          repo-cad (:repo-cadastros s)
          presidente-id (:id (repo-cadastros/vereador-por-identidade repo-cad ente (:presidente identidades)))
          ficha (repo-cadastros/ficha-vereador repo-cad ente presidente-id (LocalDate/now))
          mesa-do-presidente (some #(when (= "mesa" (:tipo %)) %) (:comissoes ficha))]
      (testing "a Mesa aparece em :comissoes da ficha — nao so' em comissao_cargo"
        (is (some? mesa-do-presidente)
            "presidente da Mesa sem entrada tipo='mesa' em :comissoes — a ficha mostraria 'Sem comissões atribuídas'"))
      (testing "o cargo do presidente na Mesa e' 'presidente' — o mesmo que a lista mostra ao lado"
        (is (= "presidente" (:cargo mesa-do-presidente))
            "cargo-mesa ficaria null na ficha, contradizendo a lista (que mostra 'PT · presidente')")))))

(deftest a-cidada-tem-vinculo-ativo-sem-papel
  ;; Defeito medido (nao cosmetico): `criar-identidades!` cria a IDENTIDADE da cidada mas nunca chamava
  ;; `vinc/criar!` p/ ela — so' secretaria/presidente/vereador ganhavam vinculo. Sem vinculo ATIVO,
  ;; `autenticacao/resolver-sessao` (fail-closed, ver docstring do ns) devolve nil p/ ela sempre: a
  ;; superficie do cidadao AUTENTICADO (`GET /portal/acompanhamentos`, `GET /meu/notificacoes` — gated
  ;; so' por `auth`, sem exigir papel) fica inalcancavel na demo. Cidadao NAO tem papel (quem trabalha na
  ;; Casa tem papel; quem so' consulta/peticiona, nao) — por isso a asserção de papel e' vazio, nao ausente.
  (with-sistema [s]
    (let [{:keys [ente identidades]} (casa/semear! s)
          ds (get-in s [:datasource :ds])
          cidadao-id (:cidadao identidades)
          vinculo-cidadao (tenancy/com-tenant* ds ente
                             (fn [tx]
                               (some #(when (= "cidadao" (:tipo %)) %)
                                     (vinc/vinculos-de tx ente cidadao-id))))]
      (testing "existe vinculo tipo 'cidadao' e ele esta ATIVO"
        (is (some? vinculo-cidadao)
            "cidada sem vinculo — resolver-sessao (fail-closed) nunca devolveria ator p/ ela")
        (is (= "ativo" (:estado vinculo-cidadao))))
      (testing "cidadao NAO tem papel — quem trabalha na Casa tem papel, quem so' consulta nao"
        (is (empty? (tenancy/com-tenant* ds ente
                      (fn [tx] (vinc/papeis-de tx ente cidadao-id))))))
      (testing "re-executar `semear!` nao duplica o vinculo (idempotente por ente,identidade,tipo)"
        (casa/semear! s)
        (is (= 1 (tenancy/com-tenant* ds ente
                   (fn [tx] (count (filter #(= "cidadao" (:tipo %))
                                            (vinc/vinculos-de tx ente cidadao-id)))))))))))
