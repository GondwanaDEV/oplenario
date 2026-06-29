(ns oplenario.sessoes.tribuna-db-test
  "INTEGRACAO (PG real): §22.6 eixo F (F4.5) — tribuna. F4.5a prova a INSCRICAO de oradores como camada de
  INTENCAO: `inscricao_oradores` com `origem_inscricao` discriminando os 4 caminhos (pre_sessao_app|
  pre_sessao_secretaria|intra_sessao_pedido|automatica_por_autoria); subordinada a FASE da pauta (reusa o enum
  de fase); vinculo OPCIONAL a `proposicao_ref_id`; pode terminar em `desistencia` (terminal) SEM gerar fala —
  intencao != execucao. `vereador_id`/`proposicao_ref_id` sao forward-ref (uuid, sem FK cross-schema, §22.10)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [malli.core :as m]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.db.sessao :as sessao]
            [oplenario.sessoes.db.tribuna :as tribuna]
            [oplenario.sessoes.logic :as logic]
            [oplenario.sessoes.models.tribuna :as mod]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- agendar! [tx ente]
  (:id (sessao/agendar! tx {:id (random-uuid) :ente-id ente :sessao-legislativa-id (random-uuid)
                            :tipo-sessao "ordinaria" :modalidade "presencial"})))

(defn- inscrever! [tx ente sid extra]
  (tribuna/inscrever!
   tx (merge {:id (random-uuid) :ente-id ente :sessao-id sid :vereador-id (random-uuid)
              :origem-inscricao "pre_sessao_secretaria" :fase "expediente" :created-by (random-uuid)} extra)))

;; ---------- vocabularios (puros) ----------

(deftest vocabularios-inscricao
  (is (thrown? Exception (logic/validar-origem-inscricao "telepatia")) "origem invalida lanca")
  (is (nil? (logic/validar-origem-inscricao "automatica_por_autoria")) "origem valida nao lanca")
  (is (true? (logic/transicao-inscricao-valida? "inscrita" "desistencia")) "inscrita -> desistencia")
  (is (false? (logic/transicao-inscricao-valida? "desistencia" "inscrita")) "desistencia e' terminal"))

;; ---------- inscrever + buscar + model ----------

(deftest inscrever-e-buscar
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (agendar! tx ente)
              pid (random-uuid)
              {iid :id} (inscrever! tx ente sid {:origem-inscricao "automatica_por_autoria"
                                                 :fase "ordem_do_dia" :proposicao-ref-id pid})
              r (tribuna/buscar-inscricao tx ente iid)]
          (is (= "automatica_por_autoria" (:origem-inscricao r)) "origem discrimina o caminho")
          (is (= "ordem_do_dia" (:fase r)) "subordinada a fase da pauta")
          (is (= pid (:proposicao-ref-id r)) "vinculo opcional a materia")
          (is (= "inscrita" (:estado r)) "nasce inscrita")
          (is (= 1 (:ordem r)) "ordem = 1 (primeira da fase)")
          (is (m/validate mod/InscricaoOrador r) "bate o model"))))))

;; ---------- ordem = fila independente por (sessao, fase) ----------

(deftest ordem-fila-por-fase
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (agendar! tx ente)
              {a :ordem} (inscrever! tx ente sid {:fase "expediente"})
              {b :ordem} (inscrever! tx ente sid {:fase "expediente"})
              {c :ordem} (inscrever! tx ente sid {:fase "ordem_do_dia"})]
          (is (= [1 2 1] [a b c]) "ordem = max+1 por (sessao, fase) — fila independente por fase"))))))

;; ---------- desistencia: intencao termina SEM fala (terminal) ----------

(deftest desistir-termina-intencao
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (agendar! tx ente)
              {iid :id} (inscrever! tx ente sid {})]
          (is (thrown? Exception (tribuna/desistir! tx {:ente-id ente :id iid :lock-version 0 :updated-by nil}))
              "desistencia sem updated-by e' barrada (trilha de auditoria, Inv.10)")
          (tribuna/desistir! tx {:ente-id ente :id iid :lock-version 0 :updated-by (random-uuid)})
          (is (= "desistencia" (:estado (tribuna/buscar-inscricao tx ente iid)))
              "inscricao pode terminar em desistencia SEM gerar fala (intencao != execucao)")
          (is (thrown? Exception
                       (tribuna/desistir! tx {:ente-id ente :id iid :lock-version 1 :updated-by (random-uuid)}))
              "desistencia e' terminal (trava re-transicao)"))))))

;; ---------- FK same-schema + RLS + listar ----------

(deftest inscricao-sessao-inexistente-barra
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (is (thrown? Exception (inscrever! tx ente (random-uuid) {}))
            "inscricao em sessao inexistente viola a FK same-schema")))))

(deftest listar-inscricoes-da-sessao
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (agendar! tx ente)]
          (inscrever! tx ente sid {:fase "expediente"})
          (inscrever! tx ente sid {:fase "ordem_do_dia"})
          (let [lst (tribuna/listar-inscricoes tx ente sid)]
            (is (= 2 (count lst)) "lista as inscricoes da sessao")
            (is (every? #(= sid (:sessao-id %)) lst) "todas da sessao")))))))
