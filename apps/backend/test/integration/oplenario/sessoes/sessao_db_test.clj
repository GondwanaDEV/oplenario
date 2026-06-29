(ns oplenario.sessoes.sessao-db-test
  "INTEGRACAO (PG real): F4.1 — §22.6 eixo A, entidade SESSAO (fundacao do HERO). Prova: numeracao canonica
  gapless por (ente, sessao_legislativa, tipo) com reset por sessao legislativa; CAPABILITIES desacopladas do
  tipo (default derivado + override auditado); maquina de estados agendada->aberta<->suspensa->encerrada /
  nao_realizada->arquivada (fail-closed); arquivada congela (trigger). sessao_legislativa_id e' forward-ref
  (uuid, sem FK cross-schema, §22.10)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.db.sessao :as sessao]
            [oplenario.sessoes.logic :as logic]
            [oplenario.sessoes.models.sessao :as mod]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- agendar! [tx ente slid extra]
  (sessao/agendar! tx (merge {:id (random-uuid) :ente-id ente :sessao-legislativa-id slid
                              :tipo-sessao "ordinaria"} extra)))

;; ---------- logica pura: capabilities + transicoes ----------

(deftest capabilities-default-e-override
  (is (false? (:delibera (logic/capabilities-default "solene"))) "solene nao delibera")
  (is (true? (:permite-voto-secreto (logic/capabilities-default "secreta"))) "secreta permite voto secreto")
  (is (true? (:delibera (logic/capabilities-default "ordinaria"))))
  ;; override parcial sobrescreve so a chave dada
  (let [caps (logic/resolver-capabilities "ordinaria" {:permite-voto-secreto true})]
    (is (true? (:permite-voto-secreto caps)) "override liga voto secreto")
    (is (true? (:delibera caps)) "resto fica no default"))
  (is (thrown? Exception (logic/capabilities-default "carnaval")) "tipo desconhecido lanca"))

(deftest transicoes-validas
  (is (logic/transicao-valida? "agendada" "aberta"))
  (is (logic/transicao-valida? "aberta" "suspensa"))
  (is (logic/transicao-valida? "suspensa" "aberta"))
  (is (logic/transicao-valida? "encerrada" "arquivada"))
  (is (not (logic/transicao-valida? "agendada" "encerrada")) "nao pula direto p/ encerrada")
  (is (not (logic/transicao-valida? "arquivada" "aberta")) "arquivada e' terminal"))

;; ---------- numeracao gapless + reset por sessao legislativa/tipo ----------

(deftest numera-gapless-com-reset
  (let [ente (random-uuid) sl-2025 (random-uuid) sl-2026 (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [a (agendar! tx ente sl-2025 {}) b (agendar! tx ente sl-2025 {})
              c (agendar! tx ente sl-2025 {:tipo-sessao "extraordinaria"})
              d (agendar! tx ente sl-2026 {})]
          (is (= [1 2] [(:numero a) (:numero b)]) "ordinarias da sessao legislativa 2025: 1,2")
          (is (= 1 (:numero c)) "extraordinaria tem sequencia propria (reset por tipo)")
          (is (= 1 (:numero d)) "outra sessao legislativa reinicia (reset por sessao legislativa)")
          (let [r (sessao/buscar tx ente (:id a))]
            (is (= "agendada" (:estado r)) "nasce agendada")
            (is (true? (:delibera r)) "capabilities materializadas")
            (is (m/validate mod/Sessao r) "sessao bate o model")))))))

;; ---------- ciclo de vida ----------

(deftest ciclo-abrir-suspender-reabrir-encerrar
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{id :id} (agendar! tx ente (random-uuid) {})]
          (sessao/transicionar! tx {:id id :ente-id ente :para "aberta" :updated-by nil :lock-version 0})
          (let [r (sessao/buscar tx ente id)]
            (is (= "aberta" (:estado r))) (is (some? (:aberta-em r)) "carimba aberta_em"))
          (sessao/transicionar! tx {:id id :ente-id ente :para "suspensa" :updated-by nil :lock-version 1})
          (sessao/transicionar! tx {:id id :ente-id ente :para "aberta" :updated-by nil :lock-version 2})
          (let [r (sessao/buscar tx ente id)]
            (is (= "aberta" (:estado r)) "reabriu da suspensao"))
          (sessao/transicionar! tx {:id id :ente-id ente :para "encerrada" :updated-by nil :lock-version 3})
          (let [r (sessao/buscar tx ente id)]
            (is (= "encerrada" (:estado r))) (is (some? (:encerrada-em r)) "carimba encerrada_em")))))))

(deftest transicao-invalida-barra
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{id :id} (agendar! tx ente (random-uuid) {})]
          (is (thrown? Exception
                       (sessao/transicionar! tx {:id id :ente-id ente :para "encerrada" :updated-by nil :lock-version 0}))
              "agendada nao vai direto p/ encerrada (maquina fail-closed)"))))))

(deftest nao-realizada-exige-motivo-e-arquiva
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{id :id} (agendar! tx ente (random-uuid) {})]
          (is (thrown? Exception
                       (sessao/transicionar! tx {:id id :ente-id ente :para "nao_realizada" :updated-by nil :lock-version 0}))
              "nao_realizada sem motivo barra (guard)")
          (sessao/transicionar! tx {:id id :ente-id ente :para "nao_realizada" :motivo "Falta de quorum"
                                    :updated-by nil :lock-version 0})
          (is (= "Falta de quorum" (:motivo-nao-realizada (sessao/buscar tx ente id))))
          (sessao/transicionar! tx {:id id :ente-id ente :para "arquivada" :updated-by nil :lock-version 1})
          (is (= "arquivada" (:estado (sessao/buscar tx ente id)))))))))

(deftest arquivada-congela
  (let [ente (random-uuid) sid (atom nil)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{id :id} (agendar! tx ente (random-uuid) {})]
          (reset! sid id)
          (sessao/transicionar! tx {:id id :ente-id ente :para "nao_realizada" :motivo "x" :updated-by nil :lock-version 0})
          (sessao/transicionar! tx {:id id :ente-id ente :para "arquivada" :updated-by nil :lock-version 1}))))
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (jdbc/execute-one! tx ["UPDATE sessoes.sessao SET modalidade = 'remota' WHERE id = ?" @sid]))))
        "sessao arquivada congela (trigger trava terminal)")))

(deftest encerrada-arquiva-e-arquivada-e-terminal
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{id :id} (agendar! tx ente (random-uuid) {})]
          (sessao/transicionar! tx {:id id :ente-id ente :para "aberta" :updated-by nil :lock-version 0})
          (sessao/transicionar! tx {:id id :ente-id ente :para "encerrada" :updated-by nil :lock-version 1})
          (sessao/transicionar! tx {:id id :ente-id ente :para "arquivada" :updated-by nil :lock-version 2})
          (is (= "arquivada" (:estado (sessao/buscar tx ente id))) "encerrada -> arquivada")
          (is (thrown? Exception
                       (sessao/transicionar! tx {:id id :ente-id ente :para "aberta" :updated-by nil :lock-version 3}))
              "arquivada e' terminal pela maquina (guard de aplicacao, nao so o trigger)"))))))

;; ---------- vocabularios ----------

(deftest tipo-invalido-barra
  (let [ente (random-uuid)]
    (is (thrown? Exception (tenancy/com-tenant* *ds* ente (fn [tx] (agendar! tx ente (random-uuid) {:tipo-sessao "festiva"})))))))
