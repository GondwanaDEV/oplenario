(ns oplenario.sessoes.db.pauta
  "Persistencia da PAUTA (§22.6 eixo B, F4.2a) — funcoes sobre a `tx` do tenant (RLS isola). pauta_sessao e'
  1:1 com a sessao (UNIQUE). Cada mutacao de item (adicionar/reordenar/remover) compoe, na MESMA tx, o ato no
  pauta_item + um registro APPEND-ONLY em pauta_alteracao. Remocao = ativo=false (NUNCA DELETE, Inv.10).
  HoneySQL schema-qualified; ente_id em toda query."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.sessoes.logic :as logic]))

(set! *warn-on-reflection* true)

(def ^:private cols-item
  [:id :ente_id :pauta_sessao_id :fase :tipo_item :proposicao_id :texto_descricao :ordem :ativo :lock_version])

(def ^:private cols-alteracao
  [:id :ente_id :pauta_sessao_id :pauta_item_id :tipo :justificativa :registrado_em])

;; ---------- pauta_sessao (container 1:1) ----------

(defn criar-pauta!
  "Cria a pauta da sessao (1:1; a UNIQUE barra segunda pauta). `sessao-id` = FK same-schema. Devolve {:id}."
  [tx {:keys [id ente-id sessao-id created-by]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :sessoes.pauta_sessao
                 :values [{:id id :ente_id ente-id :sessao_id sessao-id
                           :created_by created-by :efetivado_em [:now]}]}))
  {:id id})

(defn buscar-pauta-por-sessao [tx ente-id sessao-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select [:id :ente_id :sessao_id] :from [:sessoes.pauta_sessao]
                  :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]]}))))

;; ---------- pauta_alteracao (append-only) ----------

(defn- registrar-alteracao!
  "Grava um evento append-only de alteracao da pauta (mesma tx do ato)."
  [tx {:keys [ente-id pauta-sessao-id pauta-item-id tipo justificativa detalhe created-by]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :sessoes.pauta_alteracao
                 :values [{:id (random-uuid) :ente_id ente-id :pauta_sessao_id pauta-sessao-id
                           :pauta_item_id pauta-item-id :tipo tipo :justificativa justificativa
                           :detalhe (some-> detalhe comum/->jsonb) :created_by created-by
                           :efetivado_em [:now]}]})))

(defn listar-alteracoes [tx ente-id pauta-sessao-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols-alteracao :from [:sessoes.pauta_alteracao]
                  :where [:and [:= :ente_id ente-id] [:= :pauta_sessao_id pauta-sessao-id]]
                  :order-by [[:registrado_em :asc] [:id :asc]]}))))

;; ---------- pauta_item (mutavel) ----------

(defn buscar-item [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select cols-item :from [:sessoes.pauta_item]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn listar-itens
  "Itens ATIVOS da pauta, em ordem (sort por `ordem`, desempate por criacao)."
  [tx ente-id pauta-sessao-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols-item :from [:sessoes.pauta_item]
                  :where [:and [:= :ente_id ente-id] [:= :pauta_sessao_id pauta-sessao-id] [:= :ativo true]]
                  :order-by [[:ordem :asc] [:criado_em :asc]]}))))

(defn- proxima-ordem
  "ordem = max+1 da pauta. Sem FOR UPDATE: sob concorrencia (multiplos operadores na sessao ao vivo) dois
  itens podem colidir na mesma `ordem` — decisao deliberada (review F4.2a MENOR-4): `ordem` e' sort hint, nao
  identidade; o desempate por `criado_em` em listar-itens mantem ordenacao estavel. Se a colisao virar
  incomodo de UX, escalar p/ lock da linha pauta_sessao pai (nao UNIQUE em ordem, que quebraria a reordenacao)."
  [tx ente-id pauta-sessao-id]
  (-> (jdbc/execute-one! tx
        (sql/format {:select [[[:+ [:coalesce [:max :ordem] 0] 1] :prox]] :from [:sessoes.pauta_item]
                     :where [:and [:= :ente_id ente-id] [:= :pauta_sessao_id pauta-sessao-id]]}))
      :prox))

(defn adicionar-item!
  "Insere um item na pauta (ordem = max+1) e LOGA inclusao, atomico. Valida fase/tipo-item (fail-closed);
  a coerencia FK-por-tipo (proposicao_id XOR texto_descricao) e' barrada pelo CHECK da migration. Devolve
  {:id :ordem}."
  [tx {:keys [id ente-id pauta-sessao-id fase tipo-item proposicao-id texto-descricao created-by]}]
  (logic/validar-fase fase)
  (logic/validar-tipo-item tipo-item)
  (let [ordem (proxima-ordem tx ente-id pauta-sessao-id)]
    (jdbc/execute-one! tx
      (sql/format {:insert-into :sessoes.pauta_item
                   :values [{:id id :ente_id ente-id :pauta_sessao_id pauta-sessao-id :fase fase
                             :tipo_item tipo-item :proposicao_id proposicao-id :texto_descricao texto-descricao
                             :ordem ordem :ativo true :created_by created-by :efetivado_em [:now]}]}))
    (registrar-alteracao! tx {:ente-id ente-id :pauta-sessao-id pauta-sessao-id :pauta-item-id id
                              :tipo "inclusao" :created-by created-by})
    {:id id :ordem ordem}))

(defn- item-para-cas
  "Le o item p/ CAS (FOR UPDATE serializa mutacoes concorrentes): ordem/ativo/lock + a pauta a que pertence
  (p/ logar a alteracao). Valida existencia, lock e que o item esta ATIVO (mutar item ja removido corromperia
  o log append-only; review MAJOR-1/MENOR-3). Lanca com diagnostico preciso por caso."
  [tx ente-id id lock-version op]
  (let [{:keys [pauta-sessao-id ativo] db-lock :lock-version :as row}
        (-> (jdbc/execute-one! tx
              (sql/format {:select [:pauta_sessao_id :ordem :ativo :lock_version] :from [:sessoes.pauta_item]
                           :where [:and [:= :ente_id ente-id] [:= :id id]] :for :update}))
            comum/linha->kebab)]
    (when (nil? pauta-sessao-id)
      (throw (ex-info (str op ": item inexistente") {:id id :ente-id ente-id})))
    (when (not= db-lock lock-version)
      (throw (ex-info (str op ": conflito de lock_version") {:id id :esperado lock-version :atual db-lock})))
    (when-not ativo
      (throw (ex-info (str op ": item ja removido da pauta (ativo=false)") {:id id})))
    row))

(defn- aplicar-item!
  "UPDATE com CAS por lock_version; lanca em conflito/inexistente. `sets` ja inclui as colunas de dominio."
  [tx ente-id id lock-version sets]
  (let [r (jdbc/execute-one! tx
            (sql/format {:update :sessoes.pauta_item
                         :set (merge sets {:atualizado_em [:now] :lock_version [:+ :lock_version 1]})
                         :where [:and [:= :ente_id ente-id] [:= :id id] [:= :lock_version lock-version]]}))]
    (when (zero? (:next.jdbc/update-count r 0))
      (throw (ex-info "pauta_item: conflito de lock_version ou item inexistente"
                      {:id id :lock-version lock-version})))))

(defn reordenar-item!
  "Move o item para `nova-ordem` (CAS) e LOGA inversao com {de_ordem, para_ordem}, atomico."
  [tx {:keys [ente-id id nova-ordem updated-by lock-version]}]
  (let [{:keys [pauta-sessao-id ordem]} (item-para-cas tx ente-id id lock-version "reordenar-item!")]
    (aplicar-item! tx ente-id id lock-version {:ordem nova-ordem :updated_by updated-by})
    (registrar-alteracao! tx {:ente-id ente-id :pauta-sessao-id pauta-sessao-id :pauta-item-id id
                              :tipo "inversao" :detalhe {:de_ordem ordem :para_ordem nova-ordem}
                              :created-by updated-by})
    {:id id :de ordem :para nova-ordem}))

(defn remover-item!
  "Remocao SOFT do item (ativo=false, CAS) + LOG da alteracao, atomico. `tipo` ∈ exclusao|retirada_pedido_autor
  (fail-closed). NUNCA faz DELETE (Inv.10)."
  [tx {:keys [ente-id id tipo justificativa updated-by lock-version]}]
  (logic/validar-tipo-remocao tipo)
  (let [{:keys [pauta-sessao-id]} (item-para-cas tx ente-id id lock-version "remover-item!")]
    (aplicar-item! tx ente-id id lock-version {:ativo false :updated_by updated-by})
    (registrar-alteracao! tx {:ente-id ente-id :pauta-sessao-id pauta-sessao-id :pauta-item-id id
                              :tipo tipo :justificativa justificativa :created-by updated-by})
    {:id id :ativo false}))
