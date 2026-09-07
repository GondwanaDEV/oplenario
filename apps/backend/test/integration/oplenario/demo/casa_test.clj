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
            [oplenario.config :as config]
            [oplenario.migracao :as migracao]
            [oplenario.sistema :as sistema]))

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
        (let [alvo (io/file (System/getenv "DEMO_ARTIFACTS_DIR") "demo-ids.edn")]
          (is (.exists alvo) (str "esperado o arquivo de ids em " (.getAbsolutePath alvo)))
          (let [lido (edn/read-string (slurp alvo))]
            (is (= (:ente r1) (:ente lido)) "o ente gravado tem de ser o ente semeado")
            (is (= 17 (count (:vereadores lido))))))))))
