(ns oplenario.integracao-ia.db.orcamento
  "Persistencia do ORCAMENTO DE IA da Casa (B.9, mig 0098, ADR-0014) — funcoes sobre a `tx` do tenant. Historico
  append-only: a definicao mais recente vale."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private colunas [:id :ente_id :mensal :teto_duro :moeda :definido_por :definido_em])

(defn inserir!
  "Grava a definicao e devolve-a (com id e instante do banco)."
  [tx {:keys [ente-id mensal teto-duro moeda definido-por]}]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :integracao_ia.orcamento_ia
                  :values [{:ente_id ente-id :mensal mensal :teto_duro teto-duro :moeda moeda
                            :definido_por definido-por}]
                  :returning colunas}))))

(defn ultima
  "A definicao mais recente, CRUA — inclusive a sem valor (mig 0161: a Casa voltou a so' medir). nil = nunca houve."
  [tx ente-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:integracao_ia.orcamento_ia] :where [:= :ente_id ente-id]
                  :order-by [[:definido_em :desc] [:id :desc]] :limit 1}))))

(defn atual
  "A definicao mais recente do orcamento da Casa, ou nil (a Casa so' mede — nunca houve, ou a ultima e' sem valor)."
  [tx ente-id]
  (let [d (ultima tx ente-id)]
    (when (some? (:mensal d)) d)))

(defn ultima-exceto
  "A definicao mais recente que NAO foi feita por `definido-por` (ex.: a que valia antes da suspensao da Casa), ou nil."
  [tx ente-id definido-por]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:integracao_ia.orcamento_ia]
                  :where [:and [:= :ente_id ente-id] [:<> :definido_por definido-por]]
                  :order-by [[:definido_em :desc] [:id :desc]] :limit 1}))))
