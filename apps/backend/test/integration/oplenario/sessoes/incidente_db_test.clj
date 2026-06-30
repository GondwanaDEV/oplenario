(ns oplenario.sessoes.incidente-db-test
  "INTEGRACAO (PG real): §16.13 / §22.6 — INCIDENTES PROCESSUAIS da sessao. A auditoria de completude (§16.13)
  apontou que a sessao modelava os OUTPUTS (telao/votacao/quorum/presenca/tribuna) mas nao todos os INCIDENTES
  de conducao: questao de ordem ja' vive em `decisao_mesa` (decisao do presidente) e retirada de pauta no
  soft-remove da pauta; faltam `pedido_vista`, `verificacao_votacao`, `urgencia`, `votacao_em_bloco`.
  `incidente_processual` = ato regimental APPEND-ONLY (suscitado+deliberado num so registro, p/ a ata),
  espelha `decisao_mesa`. `objeto`/`requerente` sao forward-ref opcionais (uuid, sem FK cross-schema, §22.10).
  Os EFEITOS profundos (vista suspende+abre prazo; urgencia muda regime) sao carry F5/legislativo."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [honey.sql]
            [malli.core :as m]
            [next.jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.db.incidente :as incidente]
            [oplenario.sessoes.db.sessao :as sessao]
            [oplenario.sessoes.logic :as logic]
            [oplenario.sessoes.models.incidente :as mod]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(def ^:private f0 (java.time.Instant/parse "2026-06-29T14:00:00Z"))
(defn- mais [^java.time.Instant t s] (.plusSeconds t s))

(defn- agendar! [tx ente]
  (:id (sessao/agendar! tx {:id (random-uuid) :ente-id ente :sessao-legislativa-id (random-uuid)
                            :tipo-sessao "ordinaria" :modalidade "presencial"})))

(defn- registrar! [tx ente sid extra]
  (incidente/registrar!
   tx (merge {:id (random-uuid) :ente-id ente :sessao-id sid :tipo "pedido_vista"
              :resultado "deferido" :descricao "Pedido de vista da Proposicao 12/2026 pelo Ver. Fulano."
              :ocorrido-em f0 :created-by (random-uuid)} extra)))

;; ---------- vocabularios (puros) ----------

(deftest vocabularios-incidente
  (is (thrown? Exception (logic/validar-tipo-incidente "greve")) "tipo de incidente invalido lanca")
  (is (nil? (logic/validar-tipo-incidente "verificacao_votacao")) "tipo valido nao lanca")
  (is (thrown? Exception (logic/validar-resultado-incidente "talvez")) "resultado invalido lanca")
  (is (nil? (logic/validar-resultado-incidente "indeferido")) "resultado valido nao lanca"))

;; ---------- registrar + buscar + model ----------

(deftest registrar-e-buscar-incidente
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (agendar! tx ente)
              prop (random-uuid)
              req  (random-uuid)
              {iid :id} (registrar! tx ente sid {:tipo "pedido_vista" :resultado "deferido"
                                                 :objeto-tipo "proposicao" :objeto-id prop
                                                 :requerente-id req
                                                 :deliberacao "Vista concedida por 1 sessao, art. 132 RI."})
              r (incidente/buscar tx ente iid)]
          (is (= "pedido_vista" (:tipo r)) "tipo discrimina o incidente")
          (is (= "deferido" (:resultado r)) "resultado da deliberacao")
          (is (= "proposicao" (:objeto-tipo r)) "objeto opcional: a materia que o incidente atinge")
          (is (= prop (:objeto-id r)) "ref polimorfica forward-ref (sem FK cross-schema)")
          (is (= req (:requerente-id r)) "quem suscitou (forward-ref)")
          (is (some? (:deliberacao r)) "deliberacao opcional registrada")
          (is (m/validate mod/IncidenteProcessual r) "bate o model"))))))

;; ---------- objeto/requerente opcionais (votacao em bloco sem objeto unico) ----------

(deftest incidente-sem-objeto
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (agendar! tx ente)
              {iid :id} (registrar! tx ente sid {:tipo "votacao_em_bloco" :resultado "deferido"
                                                 :descricao "Votacao em bloco das emendas 1 a 5."})
              r (incidente/buscar tx ente iid)]
          (is (nil? (:objeto-id r)) "incidente pode existir sem objeto unico (votacao em bloco)")
          (is (nil? (:objeto-tipo r)) "objeto-tipo nulo quando nao ha objeto")
          (is (nil? (:requerente-id r)) "requerente opcional (pode ser ato da Mesa)"))))))

;; ---------- guards (auditoria + CHECKs + FK + enum) ----------

(deftest incidente-guards
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (agendar! tx ente)]
          (is (thrown? Exception (registrar! tx ente sid {:created-by nil}))
              "created-by obrigatorio (trilha de auditoria, Inv.10)")
          (is (thrown? Exception (registrar! tx ente sid {:tipo "telepatia"}))
              "tipo fora do enum lanca (logic, antes do banco)")
          (is (thrown? Exception (registrar! tx ente sid {:resultado "talvez"}))
              "resultado fora do enum lanca (logic, antes do banco)")
          (is (thrown? Exception (registrar! tx ente sid {:descricao "   "}))
              "descricao vazia viola o CHECK")
          (is (thrown? Exception (registrar! tx ente sid {:objeto-tipo "proposicao" :objeto-id nil}))
              "objeto-tipo sem objeto-id viola o CHECK de coerencia (ambos ou nenhum)")
          (is (thrown? Exception (registrar! tx ente (random-uuid) {}))
              "incidente em sessao inexistente viola a FK same-schema"))))))

;; ---------- append-only + listar cronologico ----------

(deftest incidente-append-only
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (agendar! tx ente)
              {iid :id} (registrar! tx ente sid {:ocorrido-em f0 :descricao "Primeiro."})
              _ (registrar! tx ente sid {:ocorrido-em (mais f0 600) :tipo "urgencia" :descricao "Segundo."})
              lst (incidente/listar-da-sessao tx ente sid)]
          (is (= 2 (count lst)) "lista os incidentes da sessao")
          (is (= ["Primeiro." "Segundo."] (mapv :descricao lst)) "ordem cronologica por ocorrido_em")
          (is (thrown? Exception
                       (next.jdbc/execute-one!
                        tx (honey.sql/format {:update :sessoes.incidente_processual
                                              :set {:resultado "indeferido"}
                                              :where [:and [:= :ente_id ente] [:= :id iid]]})))
              "UPDATE num incidente e' barrado (append-only: ato regimental imutavel)"))))))
