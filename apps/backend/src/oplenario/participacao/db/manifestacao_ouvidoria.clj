(ns oplenario.participacao.db.manifestacao-ouvidoria
  "Persistencia de 'participacao.manifestacao_ouvidoria' (FAST-FOLLOW Slice 5, Lei 13.460/2017 art. 10) —
  funcoes sobre a `tx` do tenant (FORCE RLS isola, mig 0042). HoneySQL schema-qualified; ente_id em TODA
  query. `protocolar!` e' o ato atomico: sequencial gapless (kernel/sequencial) + protocolo (logic) + insert,
  na MESMA tx. ANONIMA nao e' sem-auth (§22.5): `manifestante-identidade-id` chega nil ao insert quando o
  controller decide anonima?=true — o CHECK manifestacao_anonima_coerente da mig 0042 garante coerencia no
  banco. Estado evolui por CAS (transicionar-estado!), SEM DELETE (Inv.10). IMPL atras do RepoParticipacao
  (ADR-0001 §3-bis: o db/ so e' importado pelo Repo-Component)."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.kernel.sequencial :as sequencial]
            [oplenario.participacao.logic :as logic]))

(set! *warn-on-reflection* true)

(def ^:private cols
  [:id :ente_id :ano :sequencial :protocolo :tipo :assunto :descricao :anonima
   :manifestante_identidade_id :estado :recibo_em :created_by :criado_em :atualizado_em])

(defn protocolar!
  "Protocola: sequencial gapless (escopo 'manifestacao_ouvidoria:<ano>' do ente da SESSAO — le ente_id do
  GUC) + protocolo humano (logic) + insert, atomico na tx. Devolve {:id :ano :sequencial :protocolo
  :recibo-em}. `manifestante-identidade-id` DEVE vir nil do caller quando `anonima?` (o controller decide;
  aqui so' persiste o que recebeu — anti-forge/injecao ja' resolvidos upstream)."
  [tx {:keys [id ente-id ano tipo assunto descricao anonima manifestante-identidade-id recibo-em created-by]}]
  {:pre [(some? ente-id) (some? id) (some? recibo-em)
         (if anonima (nil? manifestante-identidade-id) (some? manifestante-identidade-id))]}
  (let [seq-val   (sequencial/proximo! tx (str "manifestacao_ouvidoria:" ano))
        protocolo (logic/protocolo-ouvidoria ano seq-val)]
    (jdbc/execute-one! tx
      (sql/format {:insert-into :participacao.manifestacao_ouvidoria
                   :values [{:id id :ente_id ente-id :ano ano :sequencial seq-val :protocolo protocolo
                             :tipo tipo :assunto assunto :descricao descricao :anonima (boolean anonima)
                             :manifestante_identidade_id manifestante-identidade-id
                             :estado "protocolada" :recibo_em recibo-em
                             :created_by created-by :efetivado_em [:now]}]}))
    {:id id :ano ano :sequencial seq-val :protocolo protocolo :recibo-em recibo-em}))

(defn buscar
  "Busca uma manifestacao por id no tenant (RLS via ente-id). Devolve o mapa kebab-case ou nil."
  [tx ente-id id]
  {:pre [(some? ente-id) (some? id)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select cols :from [:participacao.manifestacao_ouvidoria]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn por-protocolo
  "Busca uma manifestacao pela chave publica de acompanhamento (ente, protocolo). Devolve o mapa kebab ou
  nil. UNIQUE(ente, protocolo) garante no maximo um. Base da rota publica (a RLS isola pelo ente do path)."
  [tx ente-id protocolo]
  {:pre [(some? ente-id) (some? protocolo)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select cols :from [:participacao.manifestacao_ouvidoria]
                  :where [:and [:= :ente_id ente-id] [:= :protocolo protocolo]]}))))

(defn transicionar-estado!
  "CAS do estado: muda `de`->`para` SOMENTE se ainda esta em `de` (WHERE estado=de). Carimba atualizado_em.
  Devolve o mapa kebab se transicionou, ou nil se a corrida foi perdida (estado ja mudou entre read e write).
  A validade do grafo (logic/transicao-manifestacao-valida?) e' guardada no controller; aqui so o CAS atomico."
  [tx {:keys [id ente-id de para]}]
  {:pre [(some? ente-id) (some? id) (some? de) (some? para)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:update :participacao.manifestacao_ouvidoria
                  :set {:estado para :atualizado_em [:now]}
                  :where [:and [:= :ente_id ente-id] [:= :id id] [:= :estado de]]
                  :returning [:*]}))))

(defn responder!
  "CAS de RESPOSTA: transiciona a manifestacao p/ 'respondida' SOMENTE se ainda esta ABERTA
  (protocolada|em_analise) — `[:in :estado [inline ...]]`. Devolve o mapa kebab (incl. :protocolo) se
  transicionou, ou nil se ja estava terminal (respondida|arquivada) — a borda desambigua nil-existente p/
  409. Espelha pedido-esic/responder!."
  [tx {:keys [id ente-id]}]
  {:pre [(some? ente-id) (some? id)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:update :participacao.manifestacao_ouvidoria
                  :set {:estado "respondida" :atualizado_em [:now]}
                  :where [:and [:= :ente_id ente-id] [:= :id id]
                          [:in :estado [[:inline "protocolada"] [:inline "em_analise"]]]]
                  :returning [:*]}))))

(defn arquivar!
  "CAS de ARQUIVAMENTO (sem merito): transiciona a manifestacao p/ 'arquivada' SOMENTE se ainda esta ABERTA
  (protocolada|em_analise). Devolve o mapa kebab se transicionou, ou nil se ja estava terminal — a borda
  desambigua nil-existente p/ 409."
  [tx {:keys [id ente-id]}]
  {:pre [(some? ente-id) (some? id)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:update :participacao.manifestacao_ouvidoria
                  :set {:estado "arquivada" :atualizado_em [:now]}
                  :where [:and [:= :ente_id ente-id] [:= :id id]
                          [:in :estado [[:inline "protocolada"] [:inline "em_analise"]]]]
                  :returning [:*]}))))
