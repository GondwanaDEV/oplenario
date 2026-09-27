(ns oplenario.identidade.db.concessao-agente
  "Persistencia TENANT (FORCE RLS) da CONCESSAO de agente institucional (B.8, ADR-0013, mig `identidade-concessao-
  agente`): o `admin_ente` liga um agente da Casa (sem pessoa por tras, docs/25 3.1 b) com as classes que ele pode usar
  — so' `leitura`/`rascunho`. Revogar fecha a linha; conceder de novo abre outra. Funcoes sobre a `tx` do tenant."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(defn- ->linha [r]
  (some-> r comum/linha->kebab (update :classes #(set (.getArray ^java.sql.Array %)))))

(defn ativa
  "A concessao ATIVA do `agente` na Casa, ou nil."
  [tx ente-id agente]
  (->linha (jdbc/execute-one! tx
             (sql/format {:select [:id :ente_id :agente :classes :concedida_por :concedida_em]
                          :from [:identidade.concessao_agente]
                          :where [:and [:= :ente_id ente-id] [:= :agente agente] [:= :revogada_em nil]]}))))

(defn conceder!
  "Abre a concessao (idempotente: se ja' ha' uma ativa, devolve a ativa sem mudar nada). Dois cliques ao mesmo tempo:
  o indice unico parcial barra o segundo INSERT (a tx dele falha; a concessao existe)."
  [tx {:keys [ente-id agente classes concedida-por]}]
  {:pre [(some? ente-id) (some? agente) (seq classes) (some? concedida-por)]}
  (or (ativa tx ente-id agente)
      (->linha (jdbc/execute-one! tx
                 (sql/format {:insert-into :identidade.concessao_agente
                              :values [{:ente_id ente-id :agente agente
                                        :classes [:array (vec (sort classes)) :text]
                                        :concedida_por concedida-por}]
                              :returning [:id :ente_id :agente :classes :concedida_por :concedida_em]})))))

(defn revogar!
  "Fecha a concessao ativa do `agente` (idempotente). Devolve true se havia uma ativa."
  [tx ente-id agente revogada-por]
  (pos? (:next.jdbc/update-count
         (jdbc/execute-one! tx
           (sql/format {:update :identidade.concessao_agente
                        :set {:revogada_em [:now] :revogada_por revogada-por}
                        :where [:and [:= :ente_id ente-id] [:= :agente agente] [:= :revogada_em nil]]})))))
