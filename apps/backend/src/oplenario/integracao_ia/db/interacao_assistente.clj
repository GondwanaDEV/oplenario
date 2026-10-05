(ns oplenario.integracao-ia.db.interacao-assistente
  "O historico auditavel da Clara (mig 0220, ADR-0024): uma linha por pergunta, append-only, na tx do tenant."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum])
  (:import (java.sql Timestamp)
           (java.time Instant OffsetDateTime)))

(set! *warn-on-reflection* true)

(defn inserir!
  [tx {:keys [ente-id id conversa-id execucao-id identidade-id agente publico pergunta desfecho resposta passos
              propostas modelo execucao-ia conteudo-sha256 ocorrido-em]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :integracao_ia.interacao_assistente
                 :values [{:ente_id ente-id :id id :conversa_id conversa-id :execucao_id execucao-id
                           :identidade_id identidade-id :agente agente :publico publico :pergunta pergunta
                           :desfecho desfecho :resposta (some-> resposta comum/->jsonb)
                           :passos (comum/->jsonb passos) :propostas (comum/->jsonb propostas)
                           :modelo modelo :execucao_ia execucao-ia :conteudo_sha256 conteudo-sha256
                           :ocorrido_em (Timestamp/from ^Instant ocorrido-em)}]}))
  nil)

(defn- instante [v]
  (cond (instance? Timestamp v) (.toInstant ^Timestamp v)
        (instance? OffsetDateTime v) (.toInstant ^OffsetDateTime v)
        :else v))

(defn- linha [r]
  (-> (comum/linha->kebab r)
      (update :resposta comum/jsonb->kw)
      (update :passos comum/jsonb->kw)
      (update :propostas comum/jsonb->kw)
      (update :ocorrido-em instante)))

(defn buscar
  "A interacao `id` da Casa, ou nil."
  [tx ente-id id]
  (some-> (jdbc/execute-one! tx (sql/format {:select [:*] :from [:integracao_ia.interacao_assistente]
                                             :where [:and [:= :ente_id ente-id] [:= :id id]]}))
          linha))

(defn conversa-da-pessoa?
  "A `conversa-id` ja' existe nesta Casa e e' desta pessoa?"
  [tx ente-id identidade-id conversa-id]
  (some? (jdbc/execute-one! tx (sql/format {:select [[[:inline 1] :um]] :from [:integracao_ia.interacao_assistente]
                                            :where [:and [:= :ente_id ente-id] [:= :conversa_id conversa-id]
                                                    [:= :identidade_id identidade-id]]
                                            :limit 1}))))

(defn listar
  "As interacoes da Casa, a mais recente primeiro: de uma pessoa (`identidade-id`) ou de todas (nil, so' para o
  auditor). `antes` (Instant, opcional) pagina pelo instante. Resumo, sem a resposta: o que a lista mostra."
  [tx ente-id identidade-id antes limite]
  (mapv (fn [r] (-> (comum/linha->kebab r) (update :ocorrido-em instante)))
        (jdbc/execute! tx
          (sql/format {:select [:id :conversa_id :identidade_id :pergunta :desfecho :ocorrido_em
                                [[:raw "coalesce(jsonb_array_length(resposta -> 'citacoes'), 0)"] :n_fontes]
                                [[:jsonb_array_length :propostas] :n_propostas]]
                       :from [:integracao_ia.interacao_assistente]
                       :where (cond-> [:and [:= :ente_id ente-id]]
                                identidade-id (conj [:= :identidade_id identidade-id])
                                antes (conj [:< :ocorrido_em (Timestamp/from ^Instant antes)]))
                       :order-by [[:ocorrido_em :desc] [:id :desc]]
                       :limit limite}))))

(defn da-conversa
  "As interacoes de uma conversa da Casa, em ordem."
  [tx ente-id conversa-id]
  (mapv linha (jdbc/execute! tx (sql/format {:select [:*] :from [:integracao_ia.interacao_assistente]
                                             :where [:and [:= :ente_id ente-id] [:= :conversa_id conversa-id]]
                                             :order-by [[:ocorrido_em :asc] [:id :asc]]}))))
