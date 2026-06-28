(ns oplenario.legislativo.tramitacao-repo-test
  "INTEGRACAO (PG real): a COMPOSICAO do Repo-Component (ADR-0001 §3-bis) na tramitacao — `transicionar!`
  do RepoLegislativo roda o ENGINE + EMITE `proposicao.transicionou` no shared.outbox na MESMA tx (F3.3b).
  Atomicidade outbox-com-o-ato (§22.9 E2): a linha do evento so existe se a transicao commitou. Guard que
  bloqueia = sem transicao = sem evento. Prova o caminho de producao (via o Component, nao o db/ direto)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.relacoes.cadastro :as rel-cad]
            [oplenario.config :as config]
            [oplenario.identidade.relacoes.identidade :as rel-id]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.registro-fatos :as rf])
  (:import (java.time LocalDate)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo* nil)
(def ^:dynamic *registro* nil)

(use-fixtures :once
  (fn [t]
    (let [c   (component/start (datasource/datasource (config/carregar)))
          reg (component/start (rf/registro-fatos (merge rel-cad/relacoes rel-id/relacoes)))]
      (migracao/migrar! (:ds c))
      (binding [*ds*       (:ds c)
                *repo*     (repo/->RepoLegislativoPg c (outbox/bus))
                *registro* reg]
        (try (t) (finally (component/stop reg) (component/stop c)))))))

(def ^:private data (LocalDate/parse "2026-03-01"))

(defn- montar-template! [ente]
  (let [tid (random-uuid)]
    (repo/criar-template! *repo* ente {:id tid :chave "rito_ordinario" :versao 1
                                       :nome "Rito Ordinario [FIXTURE]" :estado-inicial "protocolada"})
    (doseq [[ch nm term] [["protocolada" "Protocolada" false] ["em_comissoes" "Em comissoes" false]
                          ["em_pauta" "Em pauta" false]]]
      (repo/criar-estado! *repo* ente {:id (random-uuid) :template-id tid :chave ch :nome nm :terminal term}))
    ;; protocolada --despachar--> em_comissoes (sem guard); em_comissoes --concluir--> em_pauta (guard 'falso')
    (repo/criar-transicao! *repo* ente {:id (random-uuid) :template-id tid :de-estado "protocolada"
                                        :para-estado "em_comissoes" :gatilho "despachar" :ordem 1})
    (repo/criar-transicao! *repo* ente {:id (random-uuid) :template-id tid :de-estado "em_comissoes"
                                        :para-estado "em_pauta" :gatilho "concluir" :guarda "falso" :ordem 1})
    tid))

(defn- eventos-transicionou [ente]
  (jdbc/execute! *ds*
    ["SELECT tipo, ente_id, payload::text AS payload FROM shared.outbox
      WHERE ente_id = ? AND tipo = 'proposicao.transicionou' ORDER BY id" ente]))

(defn- protocolar! [ente]
  (:id (repo/protocolar! *repo* ente {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                                      :uf "CE" :municipio-nome "Fortaleza" :ementa "Dispoe sobre X"})))

(deftest transicao-emite-evento-no-outbox
  (let [ente (random-uuid)
        tid  (montar-template! ente)
        pid  (protocolar! ente)]
    (is (empty? (eventos-transicionou ente)) "nada no outbox antes de transicionar")
    (let [r (repo/transicionar! *repo* ente *registro*
                                {:proposicao-id pid :template-id tid :gatilho "despachar" :agora data})]
      (is (true? (:transicionou? r)) "transicionou (guard nil)")
      (let [evs (eventos-transicionou ente)]
        (is (= 1 (count evs)) "exatamente 1 evento emitido")
        (let [pl (:payload (first evs))]
          (is (re-find #"protocolada" pl) "payload carrega o estado de origem")
          (is (re-find #"em_comissoes" pl) "payload carrega o estado de destino")
          (is (re-find #"despachar" pl) "payload carrega o gatilho")
          (is (re-find (re-pattern (str pid)) pl) "payload carrega a proposicao-id"))))))

(deftest guard-bloqueado-nao-emite-evento
  (let [ente (random-uuid)
        tid  (montar-template! ente)
        pid  (protocolar! ente)]
    ;; despachar (passa) -> 1 evento; concluir tem guard 'falso' -> bloqueia -> nao emite
    (repo/transicionar! *repo* ente *registro* {:proposicao-id pid :template-id tid :gatilho "despachar" :agora data})
    (let [antes (count (eventos-transicionou ente))
          r (repo/transicionar! *repo* ente *registro* {:proposicao-id pid :template-id tid :gatilho "concluir" :agora data})]
      (is (false? (:transicionou? r)) "guard 'falso' bloqueia")
      (is (= antes (count (eventos-transicionou ente))) "transicao bloqueada NAO emite evento"))))
