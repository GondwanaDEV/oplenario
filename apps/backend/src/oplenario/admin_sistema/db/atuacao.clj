(ns oplenario.admin-sistema.db.atuacao
  "A atuacao do operador (12.5, ADR-0016): append-only com selo ENCADEADO. Cada registro sela o anterior:
  selo = sha256(selo-anterior | id | em | operador | ente | acao | detalhe). Apagar ou reescrever um registro
  quebra a corrente dali em diante, e `verificar-corrente` aponta onde. O role da aplicacao so' insere e le'
  (sem UPDATE/DELETE, mig 0100). Os registros sao serializados por um advisory lock da tx — a corrente e' uma so'."
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.kernel.segredo :as segredo]))

(set! *warn-on-reflection* true)

(def ^:private chave-da-corrente 7162600016)   ; advisory lock da corrente (ADR-0016)

(defn- detalhe-canonico [detalhe]
  (json/write-value-as-string (into (sorted-map) (update-keys (or detalhe {}) name))))

(defn- ->instant ^java.time.Instant [v]
  (if (instance? java.sql.Timestamp v) (.toInstant ^java.sql.Timestamp v) v))

(defn selo-de
  "Pura: o selo do registro, dado o selo anterior (\"\" no primeiro)."
  [selo-anterior {:keys [id em operador-id ente-id acao detalhe]}]
  (segredo/sha256-hex (str/join "|" [selo-anterior id (str (->instant em)) (or operador-id "") (or ente-id "") acao
                                     (detalhe-canonico detalhe)])))

(defn registrar!
  "Acrescenta um registro a corrente, na tx `tx`. Devolve o registro com o selo."
  [tx {:keys [operador-id ente-id acao detalhe]}]
  {:pre [(string? acao)]}
  (jdbc/execute-one! tx ["SELECT pg_advisory_xact_lock(?)" chave-da-corrente])
  (let [anterior (or (:atuacao/selo (jdbc/execute-one! tx (sql/format {:select [:selo] :from [:admin_sistema.atuacao]
                                                                       :order-by [[:seq :desc]] :limit 1})))
                     "")
        em (->instant (:now (jdbc/execute-one! tx ["SELECT now() AS now"])))
        ;; o detalhe e' selado como ele VOLTA do jsonb (uuid vira texto etc.), senao a verificacao nao confere
        registro {:id (random-uuid) :em em :operador-id operador-id :ente-id ente-id :acao acao
                  :detalhe (json/read-value (json/write-value-as-string (or detalhe {})) json/keyword-keys-object-mapper)}
        selo (selo-de anterior registro)]
    (jdbc/execute-one! tx (sql/format {:insert-into :admin_sistema.atuacao
                                       :values [{:id (:id registro) :em em :operador_id operador-id :ente_id ente-id
                                                 :acao acao :detalhe (comum/->jsonb (:detalhe registro)) :selo selo}]}))
    (assoc registro :selo selo)))

(defn- ->registro [r]
  (-> (comum/linha->kebab r)
      (update :detalhe comum/jsonb->kw)
      (update :em ->instant)
      (dissoc :seq)))

(defn do-ente
  "A atuacao numa Casa, mais recente primeiro (o que a ficha da Casa mostra)."
  [conn ente-id limite]
  (mapv ->registro
        (jdbc/execute! conn (sql/format {:select [:seq :id :em :operador_id :ente_id :acao :detalhe :selo]
                                         :from [:admin_sistema.atuacao] :where [:= :ente_id ente-id]
                                         :order-by [[:seq :desc]] :limit limite}))))

(defn verificar-corrente
  "Recalcula a corrente inteira. {:integra? true} ou {:integra? false :quebra-em <id do 1o registro que nao confere>}."
  [conn]
  (loop [anterior "" [r & resto] (jdbc/execute! conn (sql/format {:select [:seq :id :em :operador_id :ente_id :acao
                                                                           :detalhe :selo]
                                                                  :from [:admin_sistema.atuacao]
                                                                  :order-by [[:seq :asc]]}))]
    (if-not r
      {:integra? true}
      (let [reg (->registro r)]
        (if (= (:selo reg) (selo-de anterior reg))
          (recur (:selo reg) resto)
          {:integra? false :quebra-em (:id reg)})))))
