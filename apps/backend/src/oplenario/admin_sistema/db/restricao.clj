(ns oplenario.admin-sistema.db.restricao
  "O PEDIDO de suspensao/encerramento de uma Casa e a decisao dele (ADR-0018, mig 0160). SUPRATENANT (role
  oplenario_operacao). O banco segura as duas regras que nao podem depender de disciplina: quem pede nao decide
  (`pedido_restricao_duas_pessoas`) e ha' no maximo UM pedido aberto por Casa (indice unico parcial). Toda decisao e'
  UPDATE condicional a `estado = 'aguardando'` (CAS): dois operadores decidindo ao mesmo tempo, so' um decide."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private colunas
  [:id :ente_id :acao :motivo :justificativa :estado :pedido_por :pedido_em :confirmar_ate :decidido_por :decidido_em
   :decisao_justificativa :efetivado_em])

(defn- ->instant [v] (if (instance? java.sql.Timestamp v) (.toInstant ^java.sql.Timestamp v) v))

(defn- ->pedido [r]
  (when r
    (reduce #(update %1 %2 ->instant) (comum/linha->kebab r)
            [:pedido-em :confirmar-ate :decidido-em :efetivado-em])))

(defn inserir!
  "Grava o pedido 'aguardando'. `efetivado?` = o incidente, que ja' suspende ao ser pedido."
  [conn {:keys [id ente-id acao motivo justificativa pedido-por confirmar-ate efetivado?]}]
  (->pedido (jdbc/execute-one! conn (sql/format {:insert-into :admin_sistema.pedido_restricao
                                                 :values [(cond-> {:id id :ente_id ente-id :acao acao :motivo motivo
                                                                   :justificativa justificativa :pedido_por pedido-por
                                                                   :confirmar_ate confirmar-ate}
                                                            efetivado? (assoc :efetivado_em [:now]))]
                                                 :returning colunas}))))

(defn por-id [conn id]
  (->pedido (jdbc/execute-one! conn (sql/format {:select colunas :from [:admin_sistema.pedido_restricao]
                                                 :where [:= :id id]}))))

(defn- com-nomes
  "SELECT dos pedidos com o nome da Casa e o de quem pediu."
  [where]
  {:select (into (mapv #(keyword (str "p." (name %))) colunas) [[:e.nome :casa_nome] [:o.nome :pedido_por_nome]])
   :from [[:admin_sistema.pedido_restricao :p]]
   :join [[:admin_sistema.ente :e] [:= :e.ente_id :p.ente_id]
          [:admin_sistema.operador :o] [:= :o.id :p.pedido_por]]
   :where where
   :order-by [[:p.pedido_em :asc]]})

(defn- ->pedido-com-nomes [r]
  (merge (->pedido r) (select-keys (comum/linha->kebab r) [:casa-nome :pedido-por-nome])))

(defn aberto-da-casa
  "O pedido 'aguardando' da Casa (com os nomes), ou nil."
  [conn ente-id]
  (some-> (jdbc/execute-one! conn (sql/format (com-nomes [:and [:= :p.ente_id ente-id] [:= :p.estado "aguardando"]])))
          ->pedido-com-nomes))

(defn abertos
  "A fila 'aguardando 2o operador' de todas as Casas, com o nome da Casa e de quem pediu (mais antigo primeiro)."
  [conn]
  (mapv ->pedido-com-nomes (jdbc/execute! conn (sql/format (com-nomes [:= :p.estado "aguardando"])))))

(defn decidir!
  "aguardando -> aprovado|recusado, por `decidido-por` (o banco recusa se for quem pediu). nil = ja' decidido."
  [conn id {:keys [estado decidido-por justificativa]}]
  {:pre [(#{"aprovado" "recusado"} estado)]}
  (->pedido (jdbc/execute-one! conn (sql/format {:update :admin_sistema.pedido_restricao
                                                 :set {:estado estado :decidido_por decidido-por :decidido_em [:now]
                                                       :decisao_justificativa justificativa}
                                                 :where [:and [:= :id id] [:= :estado "aguardando"]]
                                                 :returning colunas}))))

(defn fechar-sem-decisao!
  "aguardando -> expirado (o incidente sem a 2a aprovacao em 24 h) ou retirado (a Casa foi reativada antes).
  nil = ja' fechado."
  [conn id estado]
  {:pre [(#{"expirado" "retirado"} estado)]}
  (->pedido (jdbc/execute-one! conn (sql/format {:update :admin_sistema.pedido_restricao
                                                 :set {:estado estado}
                                                 :where [:and [:= :id id] [:= :estado "aguardando"]]
                                                 :returning colunas}))))

(defn marcar-efetivado! [conn id]
  (->pedido (jdbc/execute-one! conn (sql/format {:update :admin_sistema.pedido_restricao
                                                 :set {:efetivado_em [:now]}
                                                 :where [:and [:= :id id] [:= :efetivado_em nil]]
                                                 :returning colunas}))))
