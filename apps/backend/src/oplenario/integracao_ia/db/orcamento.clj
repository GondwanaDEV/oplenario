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

(defn atual
  "A definicao mais recente do orcamento da Casa, ou nil (a Casa so' mede)."
  [tx ente-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:integracao_ia.orcamento_ia] :where [:= :ente_id ente-id]
                  :order-by [[:definido_em :desc] [:id :desc]] :limit 1}))))
