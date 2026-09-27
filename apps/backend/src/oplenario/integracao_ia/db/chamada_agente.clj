(ns oplenario.integracao-ia.db.chamada-agente
  "O audit das chamadas de ferramenta de agente que escrevem (mig 0093, ADR-0010, Eixo 3.5): append-only, na tx do
  tenant."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(defn registrar!
  [tx {:keys [ente-id execucao-id identidade-id agente ferramenta classe desfecho]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :integracao_ia.chamada_agente
                 :values [{:ente_id ente-id :execucao_id execucao-id :identidade_id identidade-id :agente agente
                           :ferramenta ferramenta :classe classe :desfecho desfecho}]}))
  nil)

(defn da-execucao
  "As chamadas registradas de uma execucao, em ordem."
  [tx ente-id execucao-id]
  (comum/linhas->kebab
    (jdbc/execute! tx
      (sql/format {:select [:execucao_id :identidade_id :agente :ferramenta :classe :desfecho :ocorrido_em]
                   :from [:integracao_ia.chamada_agente]
                   :where [:and [:= :ente_id ente-id] [:= :execucao_id execucao-id]]
                   :order-by [[:ocorrido_em :asc] [:id :asc]]}))))
