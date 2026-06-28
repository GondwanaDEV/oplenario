(ns oplenario.legislativo.tramitacao-db-test
  "INTEGRACAO (PG real): eixo C — tramitacao por motor declarativo. Prova o ENGINE: do estado atual, sob
  um gatilho, escolhe a 1a transicao cujo GUARD passa (reusa o avaliador do motor, disciplina 5), grava o
  historico APPEND-ONLY e muda o estado da proposicao; guard que bloqueia = resultado normal (sem transicao).
  Usa um template FIXTURE ilustrativo (nao regulacao real — [GAP] de §22.7.5 segue GAP)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.relacoes.cadastro :as rel-cad]
            [oplenario.config :as config]
            [oplenario.identidade.relacoes.identidade :as rel-id]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.db.proposicao :as prop]
            [oplenario.legislativo.db.tramitacao :as tram]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.registro-fatos :as rf])
  (:import (java.time LocalDate)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *registro* nil)

(use-fixtures :once
  (fn [t]
    (let [c   (component/start (datasource/datasource (config/carregar)))
          ;; RegistroFatos como o sistema monta (relacoes de cadastros+identidade) — o guard nil/'falso'
          ;; nao resolve fato, mas guarda-dsl exige um registro startado (assert de costura, F2).
          reg (component/start (rf/registro-fatos (merge rel-cad/relacoes rel-id/relacoes)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c) *registro* reg]
        (try (t) (finally (component/stop reg) (component/stop c)))))))

(def ^:private data (LocalDate/parse "2026-03-01"))

(defn- montar-template! [tx ente]
  (let [tid (random-uuid)]
    (tram/criar-template! tx {:id tid :ente-id ente :chave "rito_ordinario" :versao 1
                              :nome "Rito Ordinario [FIXTURE]" :estado-inicial "protocolada"})
    (doseq [[ch nm term] [["protocolada" "Protocolada" false] ["em_comissoes" "Em comissoes" false]
                          ["em_pauta" "Em pauta" false] ["arquivada" "Arquivada" true]]]
      (tram/criar-estado! tx {:id (random-uuid) :ente-id ente :template-id tid :chave ch :nome nm :terminal term}))
    ;; protocolada --despachar--> em_comissoes (sem guard = sempre)
    (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid :de-estado "protocolada"
                               :para-estado "em_comissoes" :gatilho "despachar" :guarda nil :ordem 1})
    ;; em_comissoes --concluir--> em_pauta (guard 'falso' = sempre bloqueia, p/ testar o ramo de bloqueio)
    (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid :de-estado "em_comissoes"
                               :para-estado "em_pauta" :gatilho "concluir" :guarda "falso" :ordem 1})
    tid))

(defn- protocolar! [tx ente]
  (:id (prop/protocolar! tx {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                             :uf "CE" :municipio-nome "Fortaleza" :ementa "Dispoe sobre X"})))

(defn- transicionar [tx ente tid pid gatilho]
  (tram/transicionar! tx {:registro *registro* :ente-id ente :proposicao-id pid :template-id tid
                          :gatilho gatilho :agora data}))

(deftest engine-guard-e-mudanca-de-estado
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-template! tx ente)
              pid (protocolar! tx ente)]               ; nasce 'protocolada'
          (let [r1 (transicionar tx ente tid pid "despachar")]
            (is (true? (:transicionou? r1)) "guard nil -> transiciona")
            (is (= "em_comissoes" (:para r1)))
            (is (= "em_comissoes" (:estado (prop/buscar tx ente pid))) "estado da proposicao mudou"))
          (let [r2 (transicionar tx ente tid pid "concluir")]
            (is (false? (:transicionou? r2)) "guard 'falso' bloqueia TODAS as candidatas")
            (is (= "em_comissoes" (:estado (prop/buscar tx ente pid))) "estado inalterado apos bloqueio"))
          (let [r3 (transicionar tx ente tid pid "gatilho_inexistente")]
            (is (false? (:transicionou? r3)) "gatilho sem transicao = sem candidata"))
          (is (= 1 (count (tram/historico-da-proposicao tx ente pid)))
              "so a transicao que OCORREU foi ao historico"))))))

(deftest historico-append-only
  (let [ente (random-uuid) hid (atom nil)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-template! tx ente) pid (protocolar! tx ente)]
          (transicionar tx ente tid pid "despachar")
          (reset! hid (:id (first (tram/historico-da-proposicao tx ente pid)))))))
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (jdbc/execute-one! tx ["UPDATE legislativo.proposicao_transicao_historico SET gatilho = 'hack' WHERE id = ?" @hid]))))
        "historico de transicao e' append-only (sem UPDATE/DELETE)")))

(deftest rls-isola-template-cross-tenant
  (let [a (random-uuid) b (random-uuid) tid (atom nil)]
    (tenancy/com-tenant* *ds* a (fn [tx] (reset! tid (montar-template! tx a))))
    (is (seq (tenancy/com-tenant* *ds* a (fn [tx] (tram/transicoes-de tx a @tid "protocolada" "despachar")))) "A ve as proprias transicoes")
    (is (empty? (tenancy/com-tenant* *ds* b (fn [tx] (tram/transicoes-de tx b @tid "protocolada" "despachar")))) "B NAO ve o template de A (RLS)")))
