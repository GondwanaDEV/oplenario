(ns oplenario.legislativo.db.parecer-voto-divergente
  "Persistencia dos votos divergentes do parecer (eixo F / F3.6b) — tabela AUXILIAR append-only PURO: o
  voto vencido do membro da comissao, registrado e NUNCA alterado/apagado (Inv.10; o trigger barra
  UPDATE/DELETE). HoneySQL schema-qualified; ente_id em toda query."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private colunas [:id :ente_id :parecer_id :vereador_id :voto :justificativa :criado_em])

(defn registrar!
  "Registra um voto divergente (append-only). `voto` e' texto livre (vocabulario regimental aberto).
  Devolve {:id}."
  [tx {:keys [id ente-id parecer-id vereador-id voto justificativa]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :legislativo.parecer_voto_divergente
                 :values [{:id id :ente_id ente-id :parecer_id parecer-id :vereador_id vereador-id
                           :voto voto :justificativa justificativa :efetivado_em [:now]}]}))
  {:id id})

(defn buscar [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:legislativo.parecer_voto_divergente]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn listar-por-parecer [tx ente-id parecer-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select colunas :from [:legislativo.parecer_voto_divergente]
                  :where [:and [:= :ente_id ente-id] [:= :parecer_id parecer-id]]
                  :order-by [[:criado_em :asc]]}))))
