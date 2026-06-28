(ns oplenario.legislativo.parecer-repo-test
  "INTEGRACAO (PG real): a COMPOSICAO do Repo-Component (ADR-0001 §3-bis) no parecer (eixo F) —
  `transicionar-parecer!` do RepoLegislativo roda o ENGINE do parecer + EMITE `parecer.transicionou` no
  shared.outbox na MESMA tx (atomicidade outbox-com-o-ato, §22.9 E2). Guard que bloqueia = sem transicao
  = sem evento. Os 7 eventos tipados do eixo F + o consumer da mae sao F3.6c — aqui o evento de transicao
  unico (espelho de proposicao.transicionou) prova o caminho de producao via o Component."
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

(defn- montar-template-parecer! [ente]
  (let [tid (random-uuid)]
    (repo/criar-template! *repo* ente {:id tid :chave "parecer_ccj" :versao 1 :sujeito "parecer"
                                       :nome "Parecer CCJ [FIXTURE]" :estado-inicial "aguardando_designacao"})
    (doseq [[ch nm term] [["aguardando_designacao" "Aguardando" false] ["com_relator" "Com relator" false]
                          ["apresentado" "Apresentado" false]]]
      (repo/criar-estado! *repo* ente {:id (random-uuid) :template-id tid :chave ch :nome nm :terminal term}))
    (repo/criar-transicao! *repo* ente {:id (random-uuid) :template-id tid :de-estado "aguardando_designacao"
                                        :para-estado "com_relator" :gatilho "designar" :ordem 1})
    (repo/criar-transicao! *repo* ente {:id (random-uuid) :template-id tid :de-estado "com_relator"
                                        :para-estado "apresentado" :gatilho "bloquear" :guarda "falso" :ordem 1})
    tid))

(defn- eventos-parecer [ente]
  (jdbc/execute! *ds*
    ["SELECT tipo, ente_id, payload::text AS payload FROM shared.outbox
      WHERE ente_id = ? AND tipo = 'parecer.transicionou' ORDER BY id" ente]))

(defn- protocolar! [ente]
  (:id (repo/protocolar! *repo* ente {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                                      :uf "CE" :municipio-nome "Fortaleza" :ementa "Dispoe sobre X"})))

(deftest transicao-parecer-emite-evento-no-outbox
  (let [ente (random-uuid)
        tid  (montar-template-parecer! ente)
        pid  (protocolar! ente)
        {pcid :id} (repo/iniciar-parecer! *repo* ente {:id (random-uuid) :objeto-tipo "proposicao"
                                                       :objeto-id pid :comissao-id (random-uuid) :template-id tid})]
    (is (empty? (eventos-parecer ente)) "nada no outbox antes de transicionar")
    (let [r (repo/transicionar-parecer! *repo* ente *registro*
                                        {:parecer-id pcid :template-id tid :gatilho "designar" :agora data})]
      (is (true? (:transicionou? r)) "transicionou (guard nil)")
      (let [evs (eventos-parecer ente)]
        (is (= 1 (count evs)) "exatamente 1 evento emitido")
        (let [pl (:payload (first evs))]
          (is (re-find #"aguardando_designacao" pl) "payload carrega o estado de origem")
          (is (re-find #"com_relator" pl) "payload carrega o estado de destino")
          (is (re-find #"designar" pl) "payload carrega o gatilho")
          (is (re-find #"proposicao" pl) "payload carrega o objeto_tipo (p/ o consumer da mae em F3.6c)")
          (is (re-find (re-pattern (str pcid)) pl) "payload carrega o parecer-id"))))))

(deftest guard-bloqueado-nao-emite-evento
  (let [ente (random-uuid)
        tid  (montar-template-parecer! ente)
        pid  (protocolar! ente)
        {pcid :id} (repo/iniciar-parecer! *repo* ente {:id (random-uuid) :objeto-tipo "proposicao"
                                                       :objeto-id pid :comissao-id (random-uuid) :template-id tid})]
    (repo/transicionar-parecer! *repo* ente *registro* {:parecer-id pcid :template-id tid :gatilho "designar" :agora data})
    (let [antes (count (eventos-parecer ente))
          r (repo/transicionar-parecer! *repo* ente *registro* {:parecer-id pcid :template-id tid :gatilho "bloquear" :agora data})]
      (is (false? (:transicionou? r)) "guard 'falso' bloqueia")
      (is (= antes (count (eventos-parecer ente))) "transicao bloqueada NAO emite evento"))))
