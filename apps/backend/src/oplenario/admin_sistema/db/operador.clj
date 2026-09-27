(ns oplenario.admin-sistema.db.operador
  "O operador da plataforma (ADR-0016) e a sessao opaca do console. SUPRATENANT: roda na conexao crua do pool
  (role oplenario_operacao), nunca dentro de com-tenant*. HoneySQL."
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.kernel.segredo :as segredo]))

(set! *warn-on-reflection* true)

(defn- ->operador [r]
  (when r
    (-> (comum/linha->kebab r)
        (update :papeis #(set (some-> ^java.sql.Array % .getArray seq))))))

(def ^:private colunas [:id :email :nome :papeis :estado :criado_em :desligado_em])

(defn por-id [conn id]
  (->operador (jdbc/execute-one! conn (sql/format {:select colunas :from [:admin_sistema.operador] :where [:= :id id]}))))

(defn por-email [conn email]
  (->operador (jdbc/execute-one! conn (sql/format {:select colunas :from [:admin_sistema.operador]
                                                   :where [:= [:lower :email] (str/lower-case (str/trim email))]}))))

(defn inserir!
  "Cria o operador (papel 'operador'). E-mail ja' cadastrado -> devolve o existente (idempotente)."
  [conn {:keys [id email nome]}]
  (or (por-email conn email)
      (do (jdbc/execute-one! conn (sql/format {:insert-into :admin_sistema.operador
                                               :values [{:id id :email (str/trim email) :nome (str/trim nome)}]}))
          (por-id conn id))))

(defn desligar!
  "Estado 'desligado' + derruba TODAS as sessoes do console dele, na mesma tx do chamador."
  [conn id]
  (jdbc/execute-one! conn (sql/format {:update :admin_sistema.operador
                                       :set {:estado "desligado" :desligado_em [:now]}
                                       :where [:and [:= :id id] [:= :estado "ativo"]]}))
  (jdbc/execute-one! conn (sql/format {:delete-from :admin_sistema.sessao_operador :where [:= :operador_id id]}))
  (por-id conn id))

;; ---- sessao do console ----

(defn inserir-sessao!
  "Devolve o segredo CRU (a unica vez que ele existe fora do cookie)."
  [conn {:keys [operador-id expira-em ocioso-ate]}]
  {:pre [(some? operador-id) (some? expira-em) (some? ocioso-ate)]}
  (let [s (segredo/gerar)]
    (jdbc/execute-one! conn (sql/format {:insert-into :admin_sistema.sessao_operador
                                         :values [{:sessao_hash (segredo/sha256-bytes s) :operador_id operador-id
                                                   :expira_em expira-em :ocioso_ate ocioso-ate}]}))
    s))

(defn resolver-sessao!
  "segredo -> {:operador-id} dentro dos dois prazos (relogio do banco), deslizando a ociosidade na mesma UPDATE."
  [conn segredo janela-ociosa-seg]
  {:pre [(some? segredo) (some? janela-ociosa-seg)]}
  (some-> (jdbc/execute-one! conn
            (sql/format {:update :admin_sistema.sessao_operador
                         :set {:ocioso_ate [:+ [:now] [:raw (str "(interval '1 second' * " (long janela-ociosa-seg) ")")]]}
                         :where [:and [:= :sessao_hash (segredo/sha256-bytes segredo)]
                                 [:<= [:now] :expira_em] [:<= [:now] :ocioso_ate]]
                         :returning [:operador_id]}))
          comum/linha->kebab
          (select-keys [:operador-id])))

(defn apagar-sessao! [conn segredo]
  (jdbc/execute-one! conn (sql/format {:delete-from :admin_sistema.sessao_operador
                                       :where [:= :sessao_hash (segredo/sha256-bytes segredo)]}))
  nil)
