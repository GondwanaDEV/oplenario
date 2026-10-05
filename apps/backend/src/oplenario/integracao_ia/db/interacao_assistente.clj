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
