(ns oplenario.sessoes.db.tribuna
  "Persistencia da TRIBUNA (§22.6 eixo F) — funcoes sobre a `tx` do tenant (RLS isola). F4.5a: `inscricao_oradores`
  e' a camada de INTENCAO (intencao != execucao). `inscrever!` numera a fila (ordem = max+1 por sessao+fase);
  `desistir!` move inscrita -> desistencia (terminal) via maquina + CAS por lock_version. HoneySQL schema-qualified;
  ente_id em toda query."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.sessoes.logic :as logic]))

(set! *warn-on-reflection* true)

(def ^:private cols-inscricao
  [:id :ente_id :sessao_id :vereador_id :origem_inscricao :fase :proposicao_ref_id :estado :ordem :lock_version])

;; ---------- inscricao_oradores (intencao) ----------

(defn- proxima-ordem
  "ordem = max+1 da FILA por (sessao, fase). Sem FOR UPDATE: sob concorrencia (varios pedidos intra-sessao ao
  vivo) duas inscricoes podem colidir na mesma `ordem` — decisao deliberada (espelha pauta_item, F4.2a): `ordem`
  e' sort hint, nao identidade; o desempate por `criado_em`/`id` em listar-inscricoes mantem ordenacao estavel."
  [tx ente-id sessao-id fase]
  (-> (jdbc/execute-one! tx
        (sql/format {:select [[[:+ [:coalesce [:max :ordem] 0] 1] :prox]] :from [:sessoes.inscricao_oradores]
                     :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id] [:= :fase fase]]}))
      :prox))

(defn inscrever!
  "Inscreve um orador (camada de INTENCAO). Valida origem/fase (fail-closed); numera a fila por (sessao, fase).
  Nasce 'inscrita'. Devolve {:id :ordem}."
  [tx {:keys [id ente-id sessao-id vereador-id origem-inscricao fase proposicao-ref-id created-by]}]
  (logic/validar-origem-inscricao origem-inscricao)
  (logic/validar-fase fase)
  (when (nil? created-by)
    (throw (ex-info "inscrever!: created-by e' obrigatorio (trilha de quem inscreveu)" {:id id})))
  (let [ordem (proxima-ordem tx ente-id sessao-id fase)]
    (jdbc/execute-one! tx
      (sql/format {:insert-into :sessoes.inscricao_oradores
                   :values [{:id id :ente_id ente-id :sessao_id sessao-id :vereador_id vereador-id
                             :origem_inscricao origem-inscricao :fase fase :proposicao_ref_id proposicao-ref-id
                             :estado "inscrita" :ordem ordem :created_by created-by :efetivado_em [:now]}]}))
    {:id id :ordem ordem}))

(defn buscar-inscricao
  "Busca uma inscricao por id no tenant (RLS via ente-id). Devolve o mapa kebab-case ou nil (not-found)."
  [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select cols-inscricao :from [:sessoes.inscricao_oradores]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn listar-inscricoes
  "Inscricoes da sessao (fila), ordenadas por fase e por `ordem` dentro da fase (desempate por criacao)."
  [tx ente-id sessao-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols-inscricao :from [:sessoes.inscricao_oradores]
                  :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]]
                  :order-by [[:fase :asc] [:ordem :asc] [:criado_em :asc] [:id :asc]]}))))

(defn- estado+lock [tx ente-id id]
  (-> (jdbc/execute-one! tx
        (sql/format {:select [:estado :lock_version] :from [:sessoes.inscricao_oradores]
                     :where [:and [:= :ente_id ente-id] [:= :id id]] :for :update}))
      comum/linha->kebab))

(defn desistir!
  "Move a inscricao para 'desistencia' (terminal) via maquina logic/transicao-inscricao-valida? (fail-closed)
  com CAS por lock_version. Lanca em transicao invalida (ja desistiu), conflito de lock ou inexistente.
  Devolve {:de :para}."
  [tx {:keys [ente-id id lock-version updated-by]}]
  (when (nil? updated-by)
    (throw (ex-info "desistir!: updated-by e' obrigatorio (trilha de quem registrou a desistencia)" {:id id})))
  (let [{atual :estado db-lock :lock-version} (estado+lock tx ente-id id)]
    (when (nil? atual)
      (throw (ex-info "desistir!: inscricao inexistente" {:id id :ente-id ente-id})))
    (when (not= db-lock lock-version)
      (throw (ex-info "desistir!: conflito de lock_version" {:id id :esperado lock-version :atual db-lock})))
    (when-not (logic/transicao-inscricao-valida? atual "desistencia")
      (throw (ex-info "desistir!: transicao de estado invalida" {:id id :de atual :para "desistencia"})))
    (let [r (jdbc/execute-one! tx
              (sql/format {:update :sessoes.inscricao_oradores
                           :set {:estado "desistencia" :updated_by updated-by :atualizado_em [:now]
                                 :lock_version [:+ :lock_version 1]}
                           :where [:and [:= :ente_id ente-id] [:= :id id] [:= :lock_version lock-version]]}))]
      (when (zero? (:next.jdbc/update-count r 0))
        (throw (ex-info "desistir!: conflito de lock_version ou inexistente" {:id id :lock-version lock-version})))
      {:de atual :para "desistencia"})))
