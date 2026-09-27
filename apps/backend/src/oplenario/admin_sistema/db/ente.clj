(ns oplenario.admin-sistema.db.ente
  "O REGISTRO DE CASAS (12.1, ADR-0016) — a tabela que EMITE o ente_id. SUPRATENANT (sem RLS): o console le todas; o
  relay ativa a Casa quando o 1o administrador entra. Ciclo: provisionar -> ativo (suspenso/encerrado: fatia seguinte)."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private colunas
  [:ente_id :nome :nome_curto :uf :municipio_ibge :municipio_nome :estado :criado_em :atualizado_em
   :provisionada_por :primeiro_admin_identidade_id :primeiro_admin_email :convite_enviado_em :ativada_em])

(defn- ->instant [v] (if (instance? java.sql.Timestamp v) (.toInstant ^java.sql.Timestamp v) v))

(defn- ->casa [r]
  (when r
    (reduce #(update %1 %2 ->instant) (comum/linha->kebab r)
            [:criado-em :atualizado-em :convite-enviado-em :ativada-em])))

(defn inserir!
  [conn {:keys [ente-id nome nome-curto uf municipio-ibge municipio-nome provisionada-por primeiro-admin-email]}]
  (jdbc/execute-one! conn (sql/format {:insert-into :admin_sistema.ente
                                       :values [{:ente_id ente-id :nome nome :nome_curto nome-curto :uf uf
                                                 :municipio_ibge municipio-ibge :municipio_nome municipio-nome
                                                 :estado "provisionar" :provisionada_por provisionada-por
                                                 :primeiro_admin_email primeiro-admin-email}]})))

(defn por-id [conn ente-id]
  (->casa (jdbc/execute-one! conn (sql/format {:select colunas :from [:admin_sistema.ente] :where [:= :ente_id ente-id]}))))

(defn listar [conn]
  (mapv ->casa (jdbc/execute! conn (sql/format {:select colunas :from [:admin_sistema.ente]
                                                :order-by [[:criado_em :desc] [:ente_id :asc]]}))))

(defn marcar-primeiro-admin! [conn ente-id identidade-id]
  (jdbc/execute-one! conn (sql/format {:update :admin_sistema.ente
                                       :set {:primeiro_admin_identidade_id identidade-id :atualizado_em [:now]}
                                       :where [:= :ente_id ente-id]})))

(defn marcar-convite! [conn ente-id]
  (jdbc/execute-one! conn (sql/format {:update :admin_sistema.ente
                                       :set {:convite_enviado_em [:now] :atualizado_em [:now]}
                                       :where [:= :ente_id ente-id]})))

(defn ativar!
  "provisionar -> ativo, so' uma vez (UPDATE condicional). true = ativou agora."
  [conn ente-id]
  (some? (jdbc/execute-one! conn (sql/format {:update :admin_sistema.ente
                                              :set {:estado "ativo" :ativada_em [:now] :atualizado_em [:now]}
                                              :where [:and [:= :ente_id ente-id] [:= :estado "provisionar"]]
                                              :returning [:ente_id]}))))
