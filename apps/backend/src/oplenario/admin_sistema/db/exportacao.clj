(ns oplenario.admin-sistema.db.exportacao
  "A EXPORTACAO completa da Casa (9.6, ADR-0018 fatia 2, mig 0170). SUPRATENANT (role oplenario_operacao): a linha
  sobrevive ao apagamento dos dados da Casa — o hash da exportacao confirmada e' a prova de que entregamos. O banco
  segura: uma geracao em andamento por Casa (indice parcial), so' se confirma o que esta' PRONTO, o oficio tem texto, e
  a confirmacao nao se desfaz (trigger da mig 0175). Toda mudanca e' UPDATE condicional ao estado de origem (CAS)."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private colunas
  [:id :ente_id :solicitada_por_tipo :solicitada_por :solicitada_em :estado :chave_objeto :sha256 :bytes :manifesto
   :concluida_em :erro :confirmada_em :confirmada_por_tipo :confirmada_por :confirmacao_texto])

(defn- ->instant [v] (if (instance? java.sql.Timestamp v) (.toInstant ^java.sql.Timestamp v) v))

(defn- ->exportacao [r]
  (when r
    (-> (reduce #(update %1 %2 ->instant) (comum/linha->kebab r) [:solicitada-em :concluida-em :confirmada-em])
        (update :manifesto comum/jsonb->kw))))

(defn inserir!
  "Abre a geracao (`gerando`). Uma segunda geracao na mesma Casa estoura o indice unico (23505)."
  [conn {:keys [id ente-id solicitada-por-tipo solicitada-por]}]
  (->exportacao (jdbc/execute-one! conn (sql/format {:insert-into :admin_sistema.exportacao_casa
                                                     :values [{:id id :ente_id ente-id
                                                               :solicitada_por_tipo solicitada-por-tipo
                                                               :solicitada_por solicitada-por}]
                                                     :returning colunas}))))

(defn por-id [conn id]
  (->exportacao (jdbc/execute-one! conn (sql/format {:select colunas :from [:admin_sistema.exportacao_casa]
                                                     :where [:= :id id]}))))

(defn da-casa
  "As exportacoes da Casa, mais recente primeiro."
  [conn ente-id limite]
  (mapv ->exportacao (jdbc/execute! conn (sql/format {:select colunas :from [:admin_sistema.exportacao_casa]
                                                      :where [:= :ente_id ente-id]
                                                      :order-by [[:solicitada_em :desc] [:id :asc]]
                                                      :limit limite}))))

(defn gerando-da-casa [conn ente-id]
  (->exportacao (jdbc/execute-one! conn (sql/format {:select colunas :from [:admin_sistema.exportacao_casa]
                                                     :where [:and [:= :ente_id ente-id] [:= :estado "gerando"]]}))))

(defn concluir!
  "gerando -> pronta, com o que a geracao devolveu. nil = ja' nao estava gerando."
  [conn id {:keys [chave sha256 bytes manifesto]}]
  (->exportacao (jdbc/execute-one! conn (sql/format {:update :admin_sistema.exportacao_casa
                                                     :set {:estado "pronta" :chave_objeto chave :sha256 sha256
                                                           :bytes bytes :manifesto (comum/->jsonb (or manifesto {}))
                                                           :concluida_em [:now]}
                                                     :where [:and [:= :id id] [:= :estado "gerando"]]
                                                     :returning colunas}))))

(defn falhar!
  "gerando -> falhou, com o erro (a mensagem, nunca dado da Casa). nil = ja' nao estava gerando."
  [conn id erro]
  (->exportacao (jdbc/execute-one! conn (sql/format {:update :admin_sistema.exportacao_casa
                                                     :set {:estado "falhou" :erro erro :concluida_em [:now]}
                                                     :where [:and [:= :id id] [:= :estado "gerando"]]
                                                     :returning colunas}))))

(defn confirmar!
  "Registra o recebimento (uma vez): `tipo` = admin_ente (na tela) ou oficio (o operador, com o texto). nil = nao
  estava pronta ou ja' tinha sido confirmada."
  [conn id {:keys [tipo por texto em]}]
  (->exportacao (jdbc/execute-one! conn (sql/format {:update :admin_sistema.exportacao_casa
                                                     :set {:confirmada_em em :confirmada_por_tipo tipo
                                                           :confirmada_por por :confirmacao_texto texto}
                                                     :where [:and [:= :id id] [:= :estado "pronta"]
                                                             [:= :confirmada_em nil]]
                                                     :returning colunas}))))
