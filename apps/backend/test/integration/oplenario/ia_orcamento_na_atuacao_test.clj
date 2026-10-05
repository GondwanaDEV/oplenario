(ns oplenario.ia-orcamento-na-atuacao-test
  "INTEGRACAO (PG real) — ADR-0016 (a atuacao da Operacao) x ADR-0014 (o orcamento de IA) x ADR-0017 (adendo de
  05/10/2026): o comando `oplenario.main ia-orcamento` e' ato do OPERADOR sobre uma Casa e passa a deixar registro na
  atuacao da Operacao, que antes ficava de fora. O orcamento mora no `integracao_ia` (role de tenant) e a atuacao no
  `admin_sistema` (role do operador): nao cabem na mesma transacao sem atravessar a fronteira, entao o registro e' o PAR
  que a ADR-0017 decidiu para comando de operador com Casa — a tentativa ANTES do efeito (com o que se pretendia) e o
  desfecho DEPOIS, apontando a tentativa. Tentativa sem desfecho aparece na conferencia."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [oplenario.admin-sistema.components.repositorio :as repo-admin]
            [oplenario.admin-sistema.db.atuacao :as atuacao]
            [oplenario.config :as config]
            [oplenario.ia-orcamento :as ia-orcamento]
            [oplenario.integracao-ia.components.repositorio :as repo-ia]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.db-util :as comum]
            [oplenario.migracao :as migracao]
            [oplenario.suporte-delegante :refer [delegando]])
  (:import (java.time Instant)))

(def ^:dynamic *c* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*c* c] (try (t) (finally (component/stop c)))))))

(defn- ri [] (repo-ia/map->RepoIntegracaoIAPg {:datasource *c*}))
(defn- ro [] (assoc (repo-admin/repositorio) :datasource *c*))
(defn- deps [] {:repo-ia (ri) :repo-op (ro)})

(defn- atuacao-da-casa
  "A atuacao da Operacao nesta Casa, em ordem de gravacao (a mais antiga primeiro)."
  [ente]
  (mapv #(update % :detalhe comum/jsonb->kw)
        (jdbc/execute! (:ds *c*) ["SELECT id, acao, operador_id, ente_id, detalhe FROM admin_sistema.atuacao
                                   WHERE ente_id = ? ORDER BY seq" ente]
                       {:builder-fn rs/as-unqualified-lower-maps})))

(defn- comando [ente mensal teto & [moeda deps']]
  (ia-orcamento/definir! (or deps' (deps)) {:ente-id ente :mensal mensal :teto-duro teto :moeda moeda
                                 :definido-por "operador (linha de comando)"}))

(defn- acoes [ente] (mapv :acao (atuacao-da-casa ente)))

(deftest o-comando-deixa-o-par-na-atuacao-e-a-corrente-segue-integra
  (let [ente (random-uuid)
        d (comando ente 100M 120M)
        [tentativa desfecho :as linhas] (atuacao-da-casa ente)]
    (is (= ["ia-orcamento-iniciado" "ia-orcamento-definido"] (mapv :acao linhas))
        "o ato do operador sobre a Casa esta' na atuacao: a tentativa antes, o desfecho depois")
    (testing "o orcamento e o feed da IA foram feitos como antes"
      (is (= (:id d) (:id (repo-ia/orcamento-atual (ri) ente))))
      (is (= 1 (count (filter #(and (= "OrcamentoIADefinido" (:tipo %)) (= ente (:ente-id %)))
                              (repo-ia/listar-eventos (ri) 0 1000000))))))
    (testing "sem pessoa (a linha de comando nao tem uma): fica dito em palavras, e nao some atras de um operador nulo"
      (is (nil? (:operador_id tentativa)))
      (is (= "linha-de-comando" (get-in tentativa [:detalhe :origem])))
      (is (= "linha-de-comando" (get-in desfecho [:detalhe :origem]))))
    (testing "o que se pretendia, e o que ficou: valores, moeda, e o antes (sem orcamento = nil)"
      (is (= {:mensal "100" :teto-duro "120" :moeda "USD"} (get-in tentativa [:detalhe :depois])))
      (is (nil? (get-in tentativa [:detalhe :antes])))
      (is (= {:mensal "100" :teto-duro "120" :moeda "USD"} (get-in desfecho [:detalhe :depois]))))
    (testing "o desfecho aponta a tentativa; nada sobra sem desfecho"
      (is (= (str (:id tentativa)) (get-in desfecho [:detalhe :tentativa])))
      (is (empty? (filter #(= ente (:ente-id %)) (repo-admin/tentativas-sem-desfecho (ro) (.plusSeconds (Instant/now) 5))))))
    (testing "nenhum segredo e nenhum dado de pessoa no registro"
      (is (not-any? #(re-find #"(?i)senha|token|segredo|cpf" (pr-str (:detalhe %))) linhas)))
    (is (true? (:integra? (atuacao/verificar-corrente (:ds *c*))))
        "a cadeia de selos segue integra depois do ato novo")))

(deftest a-segunda-definicao-mostra-o-antes
  (let [ente (random-uuid)]
    (comando ente 100M 120M)
    (comando ente 200.5M 250M "BRL")
    (let [[_ _ t2 d2] (atuacao-da-casa ente)]
      (is (= {:mensal "100" :teto-duro "120" :moeda "USD"} (get-in t2 [:detalhe :antes])))
      (is (= {:mensal "200.5" :teto-duro "250" :moeda "BRL"} (get-in d2 [:detalhe :depois])))
      (is (= {:mensal "100" :teto-duro "120" :moeda "USD"} (get-in d2 [:detalhe :antes]))))
    (is (true? (:integra? (atuacao/verificar-corrente (:ds *c*)))))))

(deftest a-tentativa-entra-ANTES-do-efeito
  (let [ente (random-uuid) visto (atom nil)
        ia (delegando (ri) {:definir-orcamento!
                            (fn [real o]
                              (reset! visto {:atuacao (acoes ente) :orcamento (repo-ia/orcamento-atual real ente)})
                              (repo-ia/definir-orcamento! real o))})]
    (comando ente 10M 20M nil {:repo-ia ia :repo-op (ro)})
    (is (= ["ia-orcamento-iniciado"] (:atuacao @visto)) "no instante do efeito, a tentativa ja' esta' na corrente")
    (is (nil? (:orcamento @visto)) "e o orcamento ainda nao existe")))

(deftest definicao-recusada-pelo-banco-fecha-o-par-com-falhou-e-nao-deixa-orcamento
  (let [ente (random-uuid)]
    (is (thrown? Exception (comando ente 100M 50M)) "teto abaixo do mensal: o CHECK do banco recusa")
    (let [[t f :as linhas] (atuacao-da-casa ente)]
      (is (= ["ia-orcamento-iniciado" "ia-orcamento-falhou"] (mapv :acao linhas)))
      (is (= (str (:id t)) (get-in f [:detalhe :tentativa])) "o desfecho de falha aponta a tentativa")
      (is (string? (get-in f [:detalhe :motivo]))))
    (is (nil? (repo-ia/orcamento-atual (ri) ente)))
    (is (empty? (filter #(= ente (:ente-id %)) (repo-admin/tentativas-sem-desfecho (ro) (.plusSeconds (Instant/now) 5)))))
    (is (true? (:integra? (atuacao/verificar-corrente (:ds *c*)))))))

(deftest atuacao-fora-do-ar-na-tentativa-nada-e-feito
  (let [ente (random-uuid) chamou? (atom false)
        op (delegando (ro) {:registrar-atuacao! (fn [_ _] (throw (ex-info "banco da atuacao fora" {})))})
        ia (delegando (ri) {:definir-orcamento! (fn [_ _] (reset! chamou? true))})]
    (is (thrown-with-msg? Exception #"atuacao fora" (comando ente 10M 20M nil {:repo-ia ia :repo-op op})))
    (is (false? @chamou?) "comando de operador sem registro e' exatamente a lacuna: nao roda")
    (is (nil? (repo-ia/orcamento-atual (ri) ente)))))

(deftest desfecho-que-nao-grava-acusa-o-comando-e-a-tentativa-fica-sem-desfecho
  (let [ente (random-uuid)
        op (delegando (ro) {:registrar-atuacao!
                            (fn [real reg]
                              (if (= "ia-orcamento-definido" (:acao reg))
                                (throw (ex-info "banco da atuacao fora" {}))
                                (repo-admin/registrar-atuacao! real reg)))})]
    (is (thrown-with-msg? Exception #"definido.*atuacao" (comando ente 10M 20M nil {:repo-ia (ri) :repo-op op}))
        "o efeito aconteceu: o comando diz alto que o desfecho nao ficou registrado")
    (is (some? (repo-ia/orcamento-atual (ri) ente)) "o orcamento foi definido")
    (testing "a conferencia acusa a tentativa sem desfecho; o que ainda pode estar em curso nao e' acusado"
      (let [sem (filter #(= ente (:ente-id %)) (repo-admin/tentativas-sem-desfecho (ro) (.plusSeconds (Instant/now) 5)))]
        (is (= ["ia-orcamento-iniciado"] (mapv :acao sem))))
      (is (empty? (filter #(= ente (:ente-id %)) (repo-admin/tentativas-sem-desfecho (ro) (.minusSeconds (Instant/now) 3600))))
          "so' acusa o que tem mais de `antes-de`"))))
