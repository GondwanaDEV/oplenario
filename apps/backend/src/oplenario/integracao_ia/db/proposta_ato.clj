(ns oplenario.integracao-ia.db.proposta-ato
  "A proposta de ato (mig 0095, ADR-0012, Eixo 4.2 B) e as leituras de terceiro de uma execucao (Eixo 4.5) — funcoes
  sobre a `tx` do tenant. Estado so' anda por UPDATE condicional: quem confirma primeiro leva; a segunda confirmacao
  encontra `executando` e nao executa de novo."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private colunas
  [:id :ente_id :execucao_id :identidade_id :agente :ferramenta :entrada :titulo :texto :ritual :contaminada_por
   :estado :criada_em :expira_em :decidida_em :resultado :erro])

(defn- ->dominio [linha]
  (some-> (comum/linha->kebab linha)
          (update :entrada comum/jsonb->str)
          (update :contaminada-por comum/jsonb->kw)
          (update :resultado comum/jsonb->kw)))

(defn inserir!
  [tx {:keys [ente-id execucao-id identidade-id agente ferramenta entrada titulo texto ritual contaminada-por
              expira-em]}]
  (->dominio
    (jdbc/execute-one! tx
      (sql/format {:insert-into :integracao_ia.proposta_ato
                   :values [{:ente_id ente-id :execucao_id execucao-id :identidade_id identidade-id :agente agente
                             :ferramenta ferramenta :entrada (comum/->jsonb entrada) :titulo titulo :texto texto
                             :ritual ritual :contaminada_por (comum/->jsonb (vec contaminada-por))
                             :expira_em expira-em}]
                   :returning colunas}))))

(defn buscar [tx ente-id id]
  (->dominio (jdbc/execute-one! tx (sql/format {:select colunas :from [:integracao_ia.proposta_ato]
                                                 :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn da-pessoa
  "As propostas da pessoa ainda esperando confirmacao (e no prazo), mais novas primeiro."
  [tx ente-id identidade-id agora]
  (mapv ->dominio
        (jdbc/execute! tx (sql/format {:select colunas :from [:integracao_ia.proposta_ato]
                                       :where [:and [:= :ente_id ente-id] [:= :identidade_id identidade-id]
                                               [:= :estado "aguardando"] [:> :expira_em agora]]
                                       :order-by [[:criada_em :desc]]}))))

(defn da-execucao [tx ente-id execucao-id]
  (mapv ->dominio
        (jdbc/execute! tx (sql/format {:select colunas :from [:integracao_ia.proposta_ato]
                                       :where [:and [:= :ente_id ente-id] [:= :execucao_id execucao-id]]
                                       :order-by [[:criada_em :asc]]}))))

(defn mudar-estado!
  "UPDATE condicional: so' se o estado atual for `de`. Devolve a proposta atualizada, ou nil (outro chegou antes)."
  [tx ente-id id de {:keys [estado resultado erro decidida?]}]
  (->dominio
    (jdbc/execute-one! tx
      (sql/format {:update :integracao_ia.proposta_ato
                   :set (cond-> {:estado estado :erro erro}
                          (some? resultado) (assoc :resultado (comum/->jsonb resultado))
                          decidida? (assoc :decidida_em [:now]))
                   :where [:and [:= :ente_id ente-id] [:= :id id] [:= :estado de]]
                   :returning colunas}))))

(defn registrar-leitura-de-terceiro!
  [tx {:keys [ente-id execucao-id ferramenta origem referencia]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :integracao_ia.leitura_de_terceiro
                 :values [{:ente_id ente-id :execucao_id execucao-id :ferramenta ferramenta :origem origem
                           :referencia referencia}]}))
  nil)

(defn leituras-de-terceiro [tx ente-id execucao-id]
  (mapv #(select-keys (comum/linha->kebab %) [:ferramenta :origem :referencia])
        (jdbc/execute! tx (sql/format {:select [:ferramenta :origem :referencia] :from [:integracao_ia.leitura_de_terceiro]
                                       :where [:and [:= :ente_id ente-id] [:= :execucao_id execucao-id]]
                                       :order-by [[:lida_em :asc] [:id :asc]]}))))

(defn contar-por-estado
  "B.9: quantas propostas de ato por estado, das criadas em [desde, ate) — o painel da IA da Casa. Expirar e' lido
  pelo prazo (a expiracao so' e' gravada quando alguem abre a proposta)."
  [tx ente-id desde ate]
  (into {} (map (juxt :estado :n))
        (jdbc/execute! tx
          ["SELECT CASE WHEN estado = 'aguardando' AND expira_em < now() THEN 'expirada' ELSE estado END AS estado,
                   count(*) AS n
              FROM integracao_ia.proposta_ato
             WHERE ente_id = ? AND criada_em >= ? AND criada_em < ?
             GROUP BY 1"
           ente-id (java.sql.Timestamp/from ^java.time.Instant desde) (java.sql.Timestamp/from ^java.time.Instant ate)]
          {:builder-fn rs/as-unqualified-maps})))
