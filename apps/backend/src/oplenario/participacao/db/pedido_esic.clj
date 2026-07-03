(ns oplenario.participacao.db.pedido-esic
  "Persistencia de 'participacao.pedido_esic' — funcoes sobre a `tx` do tenant (FORCE RLS isola, mig 0039).
  HoneySQL schema-qualified (NAO e' port); ente_id em TODA query (RLS + escopo). `protocolar!` e' o ato
  atomico: sequencial gapless (kernel/sequencial) + protocolo (logic) + insert, na MESMA tx (rollback nao
  deixa buraco). Estado evolui por CAS (transicionar-estado!), SEM DELETE (Inv.10). IMPL atras do
  RepoParticipacao (ADR-0001 §3-bis: o db/ so e' importado pelo Repo-Component)."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.kernel.sequencial :as sequencial]
            [oplenario.participacao.logic :as logic]))

(set! *warn-on-reflection* true)

(def ^:private cols
  [:id :ente_id :ano :sequencial :protocolo :assunto :descricao :solicitante_identidade_id
   :estado :recibo_em :created_by :criado_em :atualizado_em])

(defn protocolar!
  "Protocola: sequencial gapless (escopo 'pedido_esic:<ano>' do ente da SESSAO — le ente_id do GUC) + protocolo
  humano (logic) + insert, atomico na tx. Devolve {:id :ano :sequencial :protocolo :recibo-em} (o numero so
  existe pos-commit). solicitante/created-by INJETADOS do ator UPSTREAM (nunca do corpo)."
  [tx {:keys [id ente-id ano assunto descricao solicitante-identidade-id recibo-em created-by]}]
  {:pre [(some? ente-id) (some? id) (some? recibo-em) (some? solicitante-identidade-id)]}
  (let [seq-val   (sequencial/proximo! tx (str "pedido_esic:" ano))
        protocolo (logic/protocolo-esic ano seq-val)]
    (jdbc/execute-one! tx
      (sql/format {:insert-into :participacao.pedido_esic
                   :values [{:id id :ente_id ente-id :ano ano :sequencial seq-val :protocolo protocolo
                             :assunto assunto :descricao descricao
                             :solicitante_identidade_id solicitante-identidade-id
                             :estado "protocolado" :recibo_em recibo-em
                             :created_by created-by :efetivado_em [:now]}]}))
    {:id id :ano ano :sequencial seq-val :protocolo protocolo :recibo-em recibo-em}))

(defn buscar
  "Busca um pedido por id no tenant (RLS via ente-id). Devolve o mapa kebab-case ou nil."
  [tx ente-id id]
  {:pre [(some? ente-id) (some? id)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select cols :from [:participacao.pedido_esic]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn por-protocolo
  "Busca um pedido pela chave publica de acompanhamento (ente, protocolo). Devolve o mapa kebab ou nil.
  UNIQUE(ente, protocolo) garante no maximo um. Base da rota publica (a RLS isola pelo ente do path)."
  [tx ente-id protocolo]
  {:pre [(some? ente-id) (some? protocolo)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select cols :from [:participacao.pedido_esic]
                  :where [:and [:= :ente_id ente-id] [:= :protocolo protocolo]]}))))

(defn listar-por-solicitante
  "'Meus pedidos' de um solicitante autenticado (ente, solicitante_identidade_id), mais recentes primeiro.
  Usa idx_pedido_esic_solicitante."
  [tx ente-id solicitante-id]
  {:pre [(some? ente-id) (some? solicitante-id)]}
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols :from [:participacao.pedido_esic]
                  :where [:and [:= :ente_id ente-id] [:= :solicitante_identidade_id solicitante-id]]
                  :order-by [[:criado_em :desc] [:id :asc]]}))))

(defn responder!
  "CAS de RESPOSTA (Slice 2): transiciona o pedido p/ 'respondido' SOMENTE se ainda esta ABERTO
  (protocolado|em_analise) — `[:in :estado [inline ...]]`. Uma resposta pode saltar direto de 'protocolado'
  (o grafo transicoes-pedido admite). Devolve o mapa kebab (incl. :protocolo) se transicionou, ou nil se o
  pedido ja estava terminal (respondido|indeferido) — a borda desambigua nil-existente p/ 409. A transicao
  p/ terminal PASSA o trg_pedido_esic_trava_terminal (so mexer numa linha JA terminal trava)."
  [tx {:keys [id ente-id]}]
  {:pre [(some? ente-id) (some? id)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:update :participacao.pedido_esic
                  :set {:estado "respondido" :atualizado_em [:now]}
                  :where [:and [:= :ente_id ente-id] [:= :id id]
                          [:in :estado [[:inline "protocolado"] [:inline "em_analise"]]]]
                  :returning [:*]}))))

(defn transicionar-estado!
  "CAS do estado: muda `de`->`para` SOMENTE se ainda esta em `de` (WHERE estado=de). Carimba atualizado_em.
  Devolve o mapa kebab se transicionou, ou nil se a corrida foi perdida (estado ja mudou entre read e write).
  A validade do grafo (logic/transicao-pedido-valida?) e' guardada no controller; aqui so o CAS atomico."
  [tx {:keys [id ente-id de para]}]
  {:pre [(some? ente-id) (some? id) (some? de) (some? para)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:update :participacao.pedido_esic
                  :set {:estado para :atualizado_em [:now]}
                  :where [:and [:= :ente_id ente-id] [:= :id id] [:= :estado de]]
                  :returning [:*]}))))
