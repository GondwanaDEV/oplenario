(ns oplenario.legislativo.db.documento-modelo
  "Persistencia do MODELO de documento (F3.9b) — template configuravel por ente (config MUTAVEL, como
  template_tramitacao). `criar!` insere; `atualizar!` edita corpo/nome (CAS); `desativar!` baixa `ativo`
  (nao se apaga). Sobre a `tx` do tenant; HoneySQL schema-qualified; ente_id em toda query."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private colunas
  [:id :ente_id :chave :nome :tipo_documento :corpo_template :ativo :lock_version])

(defn criar!
  "Cria um modelo. A UNIQUE (ente_id, chave) barra chave duplicada no ente. Devolve {:id}."
  [tx {:keys [id ente-id chave nome tipo-documento corpo-template created-by]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :legislativo.documento_modelo
                 :values [{:id id :ente_id ente-id :chave chave :nome nome :tipo_documento tipo-documento
                           :corpo_template corpo-template :created_by created-by :efetivado_em [:now]}]}))
  {:id id})

(defn buscar [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:legislativo.documento_modelo]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn buscar-por-chave [tx ente-id chave]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:legislativo.documento_modelo]
                  :where [:and [:= :ente_id ente-id] [:= :chave chave]]}))))

(defn listar-ativos [tx ente-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select colunas :from [:legislativo.documento_modelo]
                  :where [:and [:= :ente_id ente-id] [:= :ativo true]]
                  :order-by [[:tipo_documento :asc] [:nome :asc]]}))))

(defn atualizar!
  "Edita nome/corpo_template/ativo do modelo (CAS por lock_version). Lanca em conflito ou inexistente."
  [tx {:keys [id ente-id nome corpo-template ativo updated-by lock-version]}]
  (when (not-any? some? [nome corpo-template ativo])
    (throw (ex-info "atualizar!: nenhum campo a atualizar fornecido" {:id id})))
  (let [r (jdbc/execute-one! tx
            (sql/format {:update :legislativo.documento_modelo
                         :set (cond-> {:updated_by updated-by :atualizado_em [:now] :lock_version [:+ :lock_version 1]}
                                (some? nome)           (assoc :nome nome)
                                (some? corpo-template) (assoc :corpo_template corpo-template)
                                (some? ativo)          (assoc :ativo ativo))
                         :where [:and [:= :ente_id ente-id] [:= :id id] [:= :lock_version lock-version]]}))]
    (when (zero? (:next.jdbc/update-count r 0))
      (throw (ex-info "atualizar!: conflito de lock_version ou modelo inexistente"
                      {:id id :lock-version lock-version})))
    {:id id}))
