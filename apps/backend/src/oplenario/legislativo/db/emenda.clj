(ns oplenario.legislativo.db.emenda
  "Persistencia da emenda (eixo D) — funcoes sobre a `tx` do tenant (RLS isola). HoneySQL no schema
  'legislativo' (NAO e' port). `criar!` numera LOCAL por proposicao-mae (ordinal); `mudar-estado!` muda o
  ciclo (enum simples) com CAS + trigger de imutabilidade terminal; `aprovar!` aplica a emenda ao texto-mae
  na MESMA tx: cria a versao 'rascunho' (origem 'aplicacao_emenda') e fecha o ciclo bidirecional via
  versao_texto_resultante_id. Importa db/texto-versao (db->db mesmo modulo) e logic (modulo->logic puro)."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.legislativo.db.texto-versao :as texto]
            [oplenario.legislativo.logic :as logic]))

(set! *warn-on-reflection* true)

(def ^:private colunas
  ;; lock_version EXPOSTO: o caller de mudar-estado! precisa dele p/ o CAS.
  [:id :ente_id :proposicao_mae_id :numero_local :tipo_emenda :momento_apresentacao :escopo_textual
   :autor_tipo :autor_id :autor_texto :estado :formato :texto_inline :conteudo_uri :hash_conteudo
   :versao_texto_resultante_id :lock_version])

(defn criar!
  "Insere uma emenda em estado 'apresentada'. numero_local = proximo ordinal LOCAL da proposicao-mae
  (a UNIQUE (ente_id,proposicao_mae_id,numero_local) barra corrida). Conteudo XOR: passe :texto-inline OU
  :conteudo-uri (o caller decide via logic/decidir-armazenamento + objeto_store). Devolve {:id :numero-local}."
  [tx {:keys [id ente-id proposicao-mae-id tipo-emenda momento-apresentacao escopo-textual
              autor-tipo autor-id autor-texto formato texto-inline conteudo-uri hash-conteudo created-by]}]
  (let [prox (-> (jdbc/execute-one! tx
                   (sql/format {:select [[[:+ [:coalesce [:max :numero_local] 0] 1] :n]]
                                :from [:legislativo.emendas]
                                :where [:and [:= :ente_id ente-id] [:= :proposicao_mae_id proposicao-mae-id]]}))
                 :n)]
    (jdbc/execute-one! tx
      (sql/format {:insert-into :legislativo.emendas
                   :values [{:id id :ente_id ente-id :proposicao_mae_id proposicao-mae-id :numero_local prox
                             :tipo_emenda tipo-emenda :momento_apresentacao momento-apresentacao
                             :escopo_textual escopo-textual :autor_tipo autor-tipo :autor_id autor-id
                             :autor_texto autor-texto :estado "apresentada" :formato (or formato "markdown")
                             :texto_inline texto-inline :conteudo_uri conteudo-uri :hash_conteudo hash-conteudo
                             :created_by created-by :efetivado_em [:now]}]}))
    {:id id :numero-local prox}))

(defn buscar [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:legislativo.emendas]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn listar-por-mae [tx ente-id proposicao-mae-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select colunas :from [:legislativo.emendas]
                  :where [:and [:= :ente_id ente-id] [:= :proposicao_mae_id proposicao-mae-id]]
                  :order-by [[:numero_local :asc]]}))))

(defn mudar-estado!
  "Transicao do ciclo (enum simples) com CAS por `lock-version` (compare-and-swap honesto). O trigger
  compartilhado trava a transicao A PARTIR de um estado terminal (exceto correcao auditada). Lanca em
  conflito de versao OU row inexistente (0 linhas afetadas). Recusa 'aprovada' (review F3.4 DB-M2): a
  aprovacao exige criar a versao resultante na MESMA tx — e' aprovar!, nao um set solto de estado."
  [tx {:keys [id ente-id estado updated-by lock-version]}]
  (when (= "aprovada" estado)
    (throw (ex-info "use aprovar! para aprovar emenda (requer criar a versao de texto atomicamente)"
                    {:id id :estado estado})))
  (let [r (jdbc/execute-one! tx
            (sql/format {:update :legislativo.emendas
                         :set {:estado estado :updated_by updated-by :atualizado_em [:now]
                               :lock_version [:+ :lock_version 1]}
                         :where [:and [:= :ente_id ente-id] [:= :id id] [:= :lock_version lock-version]]}))]
    (when (zero? (:next.jdbc/update-count r 0))
      (throw (ex-info "conflito de escrita (lock_version desatualizado) ou emenda inexistente"
                      {:id id :lock-version lock-version})))
    r))

(defn- emenda+lock
  "Le estado+lock+mae da emenda SOB FOR UPDATE: serializa aprovacoes concorrentes na MESMA emenda."
  [tx ente-id emenda-id]
  (-> (jdbc/execute-one! tx
        (sql/format {:select [:estado :lock_version :proposicao_mae_id] :from [:legislativo.emendas]
                     :where [:and [:= :ente_id ente-id] [:= :id emenda-id]]
                     :for :update}))
      comum/linha->kebab))

(defn aprovar!
  "Aplica a emenda APROVADA ao texto-mae (§22.4 eixo D) — atomico na tx (o caller abre via Repo/transacao):
  (1) cria uma proposicao_texto_versao 'rascunho' da proposicao-mae (origem 'aplicacao_emenda', origem_ref =
  emenda-id) — o ponto de partida que o redator HUMANO consolida (promocao a vigente e' ato explicito do eixo
  B, NAO automatica aqui); (2) leva a emenda a 'aprovada' e grava versao_texto_resultante_id (fecha o ciclo
  bidirecional), com CAS por lock_version. O seed do rascunho (`:versao`) e' input do caller (app/IA), nao
  conteudo inventado aqui. Fail-closed: emenda ja-terminal lanca antes de tocar o banco. Devolve
  {:emenda-id :estado 'aprovada' :versao-resultante-id :numero-versao}."
  [tx {:keys [ente-id emenda-id updated-by versao]}]
  (let [{:keys [estado lock-version proposicao-mae-id]} (emenda+lock tx ente-id emenda-id)]
    (when (nil? estado)
      (throw (ex-info "aprovar!: emenda inexistente" {:emenda-id emenda-id :ente-id ente-id})))
    ;; fail-closed: so de estado NAO-terminal se aprova (apresentada|admitida). A precondicao FINA de
    ;; admissibilidade (exigir 'admitida'? trilho rapido p/ emenda de plenario?) e' regimental [GAP] —
    ;; deferida ao especialista em regimento (§22.4.4), nao se crava aqui (review F3.4 DB-M1).
    (when (contains? logic/estados-emenda-terminais estado)
      (throw (ex-info "aprovar!: emenda ja esta em estado terminal" {:emenda-id emenda-id :estado estado})))
    ;; proveniencia CRAVADA por aprovar! (review F3.4 clojure-MAJOR): so os campos de CONTEUDO vem do seed
    ;; do caller; origem/ref/tipo/tenant/proposicao nao se sobrescrevem (texto_versao e' append-only).
    (let [{:keys [id formato texto-inline conteudo-uri hash-conteudo created-by]} versao
          {vid :id n :numero-versao}
          (texto/nova-versao! tx {:id id :ente-id ente-id :proposicao-id proposicao-mae-id
                                  :origem-versao "aplicacao_emenda" :origem-ref emenda-id :origem-tipo "emenda"
                                  :formato formato :texto-inline texto-inline :conteudo-uri conteudo-uri
                                  :hash-conteudo hash-conteudo :created-by created-by})
          r (jdbc/execute-one! tx
              (sql/format {:update :legislativo.emendas
                           :set {:estado "aprovada" :versao_texto_resultante_id vid
                                 :updated_by updated-by :atualizado_em [:now] :lock_version [:+ :lock_version 1]}
                           :where [:and [:= :ente_id ente-id] [:= :id emenda-id]
                                   [:= :lock_version lock-version]]}))]
      (when (zero? (:next.jdbc/update-count r 0))
        (throw (ex-info "aprovar!: conflito de lock_version (emenda mudou no meio da aprovacao)"
                        {:emenda-id emenda-id :lock-version lock-version})))
      {:emenda-id emenda-id :estado "aprovada" :versao-resultante-id vid :numero-versao n})))
