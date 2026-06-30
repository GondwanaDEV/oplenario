(ns oplenario.sessoes.db.incidente
  "Persistencia dos INCIDENTES PROCESSUAIS (§16.13) — funcoes sobre a `tx` do tenant (RLS isola). Ato regimental
  APPEND-ONLY: o incidente e' suscitado+deliberado uma vez; corrigir = novo incidente (nunca UPDATE), padrao
  decisao_mesa/presenca_evento. `objeto`/`requerente` sao forward-ref opcionais (sem FK cross-schema, §22.10).
  HoneySQL schema-qualified; ente_id em toda query."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.sessoes.logic :as logic]))

(set! *warn-on-reflection* true)

(def ^:private cols
  [:id :ente_id :sessao_id :tipo :resultado :descricao :objeto_tipo :objeto_id :requerente_id
   :deliberacao :ocorrido_em])

(defn registrar!
  "Registra um incidente processual (ato regimental p/ a ata, APPEND-ONLY). Valida tipo/resultado como GUARDA DE
  PROFUNDIDADE (a borda ja recusou -> 400; aqui lanca ex-info CRUA -> 500 se escapar, sinalizando bug de servidor,
  nao input de cliente) + nil-guard de auditoria (created-by). A coerencia objeto-tipo<->objeto-id e o nao-vazio
  de descricao/deliberacao sao os CHECK da migration. `objeto-*`/`requerente-id`/`deliberacao` opcionais. Devolve {:id}."
  [tx {:keys [id ente-id sessao-id tipo resultado descricao objeto-tipo objeto-id requerente-id
              deliberacao ocorrido-em created-by]}]
  (logic/validar-tipo-incidente tipo)
  (logic/validar-resultado-incidente resultado)
  (when (nil? created-by)
    (throw (ex-info "registrar!: created-by e' obrigatorio (trilha de auditoria)" {:id id})))
  (jdbc/execute-one! tx
    (sql/format {:insert-into :sessoes.incidente_processual
                 :values [{:id id :ente_id ente-id :sessao_id sessao-id :tipo tipo :resultado resultado
                           :descricao descricao :objeto_tipo objeto-tipo :objeto_id objeto-id
                           :requerente_id requerente-id :deliberacao deliberacao :ocorrido_em ocorrido-em
                           :created_by created-by :efetivado_em [:now]}]}))
  {:id id})

(defn buscar
  "Busca um incidente por id no tenant (RLS via ente-id). Devolve o mapa kebab-case ou nil (not-found)."
  [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select cols :from [:sessoes.incidente_processual]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn listar-da-sessao
  "Incidentes da sessao em ordem cronologica (composicao da ata + painel da mesa de conducao)."
  [tx ente-id sessao-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols :from [:sessoes.incidente_processual]
                  :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]]
                  :order-by [[:ocorrido_em :asc] [:id :asc]]}))))
