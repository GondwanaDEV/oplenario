(ns oplenario.participacao.db.recurso-esic
  "Persistencia de 'participacao.recurso_esic' (F6 Slice 2) — funcoes sobre a `tx` do tenant (FORCE RLS
  isola, mig 0040). O recurso e' ENTIDADE SEPARADA do pedido (relogio proprio); `inserir!` e' o ato atomico
  (sequencial gapless 'recurso_esic:<ano>' + protocolo humano + insert, na MESMA tx — rollback nao deixa
  buraco). Estado evolui por CAS (transicionar-estado!), SEM DELETE (Inv.10). O 'decidido' e' terminal
  (carimba decidido_em na transicao). HoneySQL schema-qualified; ente_id em TODA query. IMPL atras do
  RepoParticipacao (ADR-0001 §3-bis: o db/ so e' importado pelo Repo-Component)."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.kernel.sequencial :as sequencial]
            [oplenario.participacao.logic :as logic]))

(set! *warn-on-reflection* true)

(def ^:private cols
  [:id :ente_id :pedido_id :ano :sequencial :protocolo :instancia :motivo :estado
   :recibo_em :decidido_em :created_by :criado_em :atualizado_em])

(defn inserir!
  "Interpoe um recurso: sequencial gapless (escopo 'recurso_esic:<ano>' do ente da SESSAO — le ente_id do GUC)
  + protocolo humano (logic) + insert, atomico na tx. instancia = ordinal da instancia recursal (V1 = 1).
  created-by/motivo INJETADOS upstream (motivo do cidadao; created-by do ator). Devolve o mapa kebab da linha."
  [tx {:keys [id ente-id pedido-id ano instancia motivo recibo-em created-by]}]
  {:pre [(some? ente-id) (some? id) (some? pedido-id) (some? recibo-em) (some? instancia)]}
  (let [seq-val   (sequencial/proximo! tx (str "recurso_esic:" ano))
        protocolo (logic/protocolo-recurso ano seq-val)]
    (comum/linha->kebab
     (jdbc/execute-one! tx
       (sql/format {:insert-into :participacao.recurso_esic
                    :values [{:id id :ente_id ente-id :pedido_id pedido-id :ano ano :sequencial seq-val
                              :protocolo protocolo :instancia instancia :motivo motivo
                              :estado "protocolado" :recibo_em recibo-em
                              :created_by created-by :efetivado_em [:now]}]
                    :returning [:*]})))))

(defn buscar
  "Busca um recurso por id no tenant (RLS via ente-id). Devolve o mapa kebab-case ou nil."
  [tx ente-id id]
  {:pre [(some? ente-id) (some? id)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select cols :from [:participacao.recurso_esic]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn por-protocolo
  "Busca um recurso pela chave publica (ente, protocolo). UNIQUE(ente, protocolo) garante no maximo um."
  [tx ente-id protocolo]
  {:pre [(some? ente-id) (some? protocolo)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select cols :from [:participacao.recurso_esic]
                  :where [:and [:= :ente_id ente-id] [:= :protocolo protocolo]]}))))

(defn transicionar-estado!
  "CAS do estado: muda `de`->`para` SOMENTE se ainda esta em `de` (WHERE estado=de). `extra` = mapa de colunas
  adicionais a carimbar atomicamente na MESMA transicao (ex.: {:decidido_em <instant>} na decisao — a CHECK
  recurso_decidido_coerente exige decidido_em nao-nulo em 'decidido'). Devolve o mapa kebab se transicionou,
  ou nil se a corrida foi perdida (estado ja mudou). A validade do grafo e' guardada no controller/servico."
  [tx {:keys [id ente-id de para extra]}]
  {:pre [(some? ente-id) (some? id) (some? de) (some? para)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:update :participacao.recurso_esic
                  :set (merge {:estado para :atualizado_em [:now]} extra)
                  :where [:and [:= :ente_id ente-id] [:= :id id] [:= :estado de]]
                  :returning [:*]}))))
