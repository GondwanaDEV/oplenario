(ns oplenario.legislativo.db.texto-versao
  "Persistencia do versionamento de texto (eixo B) — funcoes sobre a `tx` do tenant. Conteudo append-only
  (trigger congela); `promover!` e' o ato auditado rascunho->vigente (supersede a anterior + reaponta o
  pointer da proposicao, na MESMA tx). Toda query inclui ente_id (tabela hash-particionada). HoneySQL."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private colunas
  ;; lock_version EXPOSTO: o caller de promover! precisa dele p/ o CAS (review F3.2 MAJOR-2).
  [:id :ente_id :proposicao_id :numero_versao :origem_versao :origem_ref :origem_tipo :estado_versao
   :formato :texto_inline :conteudo_uri :hash_conteudo :lock_version])

(defn- linha->versao [linha] (comum/linha->kebab linha))

(defn nova-versao!
  "Insere uma versao NOVA em estado 'rascunho'. numero_versao = proximo ordinal local da proposicao
  (a UNIQUE (ente_id,proposicao_id,numero_versao) barra corrida). Conteudo XOR: passe :texto-inline OU
  :conteudo-uri (o caller decide via logic/decidir-armazenamento + objeto_store). Devolve {:id :numero-versao}."
  [tx {:keys [id ente-id proposicao-id origem-versao origem-ref origem-tipo formato
              texto-inline conteudo-uri hash-conteudo created-by]}]
  (let [prox (-> (jdbc/execute-one! tx
                   (sql/format {:select [[[:+ [:coalesce [:max :numero_versao] 0] 1] :n]]
                                :from [:legislativo.proposicao_texto_versao]
                                :where [:and [:= :ente_id ente-id] [:= :proposicao_id proposicao-id]]}))
                 :n)]
    (jdbc/execute-one! tx
      (sql/format {:insert-into :legislativo.proposicao_texto_versao
                   :values [{:id id :ente_id ente-id :proposicao_id proposicao-id :numero_versao prox
                             :origem_versao origem-versao :origem_ref origem-ref :origem_tipo origem-tipo
                             :estado_versao "rascunho" :formato (or formato "markdown")
                             :texto_inline texto-inline :conteudo_uri conteudo-uri :hash_conteudo hash-conteudo
                             :created_by created-by :efetivado_em [:now]}]}))
    {:id id :numero-versao prox}))

(defn promover!
  "Promove a versao `versao-id` a 'vigente' (ato auditado, eixo B): supersede a vigente anterior da
  proposicao, marca a alvo como vigente (CAS por lock-version) e reaponta proposicoes.texto_vigente_versao_id
  — tudo na MESMA tx (o caller abre via Repo/transacao). Lanca em conflito de versao OU versao inexistente."
  [tx {:keys [ente-id proposicao-id versao-id updated-by lock-version]}]
  ;; 1) a vigente anterior (se houver, e != alvo) -> superada
  (jdbc/execute-one! tx
    (sql/format {:update :legislativo.proposicao_texto_versao
                 :set {:estado_versao "superada" :updated_by updated-by :atualizado_em [:now]
                       :lock_version [:+ :lock_version 1]}
                 :where [:and [:= :ente_id ente-id] [:= :proposicao_id proposicao-id]
                         [:= :estado_versao "vigente"] [:<> :id versao-id]]}))
  ;; 2) a alvo -> vigente (CAS). proposicao_id no WHERE: a versao TEM de pertencer a esta proposicao —
  ;; senao um versao-id de outra proposicao (com lock coincidente) seria promovido e o pointer apontaria
  ;; p/ texto alheio (review F3.2 MAJOR-1).
  (let [r (jdbc/execute-one! tx
            (sql/format {:update :legislativo.proposicao_texto_versao
                         :set {:estado_versao "vigente" :updated_by updated-by :atualizado_em [:now]
                               :lock_version [:+ :lock_version 1]}
                         :where [:and [:= :ente_id ente-id] [:= :proposicao_id proposicao-id]
                                 [:= :id versao-id] [:= :lock_version lock-version]]}))]
    (when (zero? (:next.jdbc/update-count r 0))
      (throw (ex-info "promover!: conflito de lock_version ou versao inexistente nesta proposicao"
                      {:versao-id versao-id :proposicao-id proposicao-id :lock-version lock-version})))
    ;; 3) reaponta o pointer da proposicao-mae (§22.4 eixo B); checa update-count (RLS/staging poderia
    ;; filtrar a proposicao e deixar o pointer inconsistente em silencio — review F3.2 MENOR-3).
    (let [rp (jdbc/execute-one! tx
               (sql/format {:update :legislativo.proposicoes
                            :set {:texto_vigente_versao_id versao-id :atualizado_em [:now]
                                  :lock_version [:+ :lock_version 1]}
                            :where [:and [:= :ente_id ente-id] [:= :id proposicao-id]]}))]
      (when (zero? (:next.jdbc/update-count rp 0))
        (throw (ex-info "promover!: proposicao inexistente ou filtrada (pointer nao reapontado)"
                        {:proposicao-id proposicao-id :ente-id ente-id}))))
    {:versao-id versao-id :estado "vigente"}))

(defn buscar [tx ente-id id]
  (linha->versao
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:legislativo.proposicao_texto_versao]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn versoes-da-proposicao [tx ente-id proposicao-id]
  (mapv linha->versao
        (jdbc/execute! tx
          (sql/format {:select colunas :from [:legislativo.proposicao_texto_versao]
                       :where [:and [:= :ente_id ente-id] [:= :proposicao_id proposicao-id]]
                       :order-by [[:numero_versao :asc]]}))))

(defn vigente [tx ente-id proposicao-id]
  (linha->versao
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:legislativo.proposicao_texto_versao]
                  :where [:and [:= :ente_id ente-id] [:= :proposicao_id proposicao-id]
                          [:= :estado_versao "vigente"]]}))))
