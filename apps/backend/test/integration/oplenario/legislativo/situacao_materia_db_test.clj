(ns oplenario.legislativo.situacao-materia-db-test
  "INTEGRACAO (PG real): ADR-0019 fatia 3 — a situacao de parecer de um LOTE de materias, pelo Repo-Component do
  legislativo (o seam que o host entrega a `sessoes` para os avisos da pauta). Prova: parecer emitido x em andamento x
  prazo vencido (que NAO conta como parecer), pedido juridico pendente (o atendido/cancelado nao conta), parecer sobre
  EMENDA nao conta para a materia, materia de outra Casa ou inexistente nao volta."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.cadastros.relacoes.cadastro :as rel-cad]
            [oplenario.config :as config]
            [oplenario.identidade.relacoes.identidade :as rel-id]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.legislativo.components.repositorio-situacao :as rs]
            [oplenario.legislativo.db.parecer :as parecer]
            [oplenario.legislativo.db.parecer-juridico :as pj]
            [oplenario.legislativo.db.parecer-tramitacao :as ptram]
            [oplenario.legislativo.db.proposicao :as prop]
            [oplenario.legislativo.db.tramitacao :as tram]
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
      (binding [*ds* (:ds c) *repo* (repo/->RepoLegislativoPg c (outbox/bus)) *registro* reg]
        (try (t) (finally (component/stop reg) (component/stop c)))))))

(defn- template-parecer! [tx ente]
  (let [tid (random-uuid)]
    (tram/criar-template! tx {:id tid :ente-id ente :chave "parecer_ccj" :versao 1 :sujeito "parecer"
                              :nome "Parecer [FIXTURE]" :estado-inicial "aguardando_designacao"})
    (doseq [[ch term] [["aguardando_designacao" false] ["aprovado" true] ["prazo_vencido" true]]]
      (tram/criar-estado! tx {:id (random-uuid) :ente-id ente :template-id tid :chave ch :nome ch :terminal term}))
    (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid :de-estado "aguardando_designacao"
                               :para-estado "aprovado" :gatilho "emitir" :guarda nil :ordem 1})
    (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid :de-estado "aguardando_designacao"
                               :para-estado "prazo_vencido" :gatilho "vencer" :guarda nil :ordem 1})
    tid))

(defn- materia! [tx ente]
  (:id (prop/protocolar! tx {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                             :municipio-nome "Fortaleza" :ementa "Dispoe sobre X"})))

(defn- parecer! [tx ente tid tipo objeto gatilho]
  (let [{pcid :id} (parecer/criar! tx {:id (random-uuid) :ente-id ente :objeto-tipo tipo :objeto-id objeto
                                       :comissao-id (random-uuid) :template-id tid})]
    (when gatilho
      (ptram/transicionar-parecer! tx {:registro *registro* :ente-id ente :parecer-id pcid :template-id tid
                                       :gatilho gatilho :agora (LocalDate/parse "2026-09-30")}))
    pcid))

(deftest situacao-de-parecer-em-lote
  (let [ente (random-uuid)
        {:keys [emitido andamento vencido so-juridico nada]}
        (tenancy/com-tenant* *ds* ente
          (fn [tx]
            (let [tid (template-parecer! tx ente)
                  m (into {} (map (fn [k] [k (materia! tx ente)])) [:emitido :andamento :vencido :so-juridico :nada])]
              (parecer! tx ente tid "proposicao" (:emitido m) "emitir")
              (parecer! tx ente tid "proposicao" (:andamento m) nil)
              (parecer! tx ente tid "proposicao" (:vencido m) "vencer")
              (pj/criar-pedido! tx {:ente-id ente :proposicao-id (:so-juridico m) :assunto "Analise" :origem "secretaria"
                                    :pedido-por ente})
              (let [atendido (pj/criar-pedido! tx {:ente-id ente :proposicao-id (:emitido m) :assunto "Analise"
                                                   :origem "secretaria" :pedido-por ente})]
                (pj/cancelar-pedido! tx ente (:id atendido) ente))
              m)))
        s (rs/situacao-de-parecer-das-materias *repo* ente #{emitido andamento vencido so-juridico nada (random-uuid)})]
    (is (= {:pareceres-emitidos 1 :pareceres-em-andamento 0 :pedidos-juridicos-pendentes 0} (get s emitido))
        "o pedido cancelado nao conta")
    (is (= {:pareceres-emitidos 0 :pareceres-em-andamento 1 :pedidos-juridicos-pendentes 0} (get s andamento)))
    (is (= {:pareceres-emitidos 0 :pareceres-em-andamento 0 :pedidos-juridicos-pendentes 0} (get s vencido))
        "prazo vencido e' a comissao NAO ter se manifestado")
    (is (= 1 (:pedidos-juridicos-pendentes (get s so-juridico))))
    (is (= 5 (count s)) "id inexistente nao volta")
    (is (= {} (rs/situacao-de-parecer-das-materias *repo* (random-uuid) [emitido])) "materia de outra Casa nao volta")
    (is (= {} (rs/situacao-de-parecer-das-materias *repo* ente [])))))
