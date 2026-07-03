(ns oplenario.participacao.db.solicitacao-titular
  "Persistencia de 'participacao.solicitacao_titular' (F6 Slice 4, LGPD art. 18) — funcoes sobre a `tx` do
  tenant (FORCE RLS isola, mig 0041). HoneySQL schema-qualified (NAO e' port); ente_id em TODA query (RLS +
  escopo). `protocolar!` e' o ato atomico: sequencial gapless (escopo 'solicitacao_titular:<ano>') + protocolo
  humano (logic) + insert, na MESMA tx (rollback nao deixa buraco). Estado evolui por CAS (responder!), SEM
  DELETE (Inv.10). `respondida`/`indeferida` sao terminais. IMPL atras do RepoParticipacao (ADR-0001 §3-bis:
  o db/ so e' importado pelo Repo-Component)."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.kernel.sequencial :as sequencial]
            [oplenario.participacao.logic :as logic]))

(set! *warn-on-reflection* true)

(def ^:private cols
  [:id :ente_id :ano :sequencial :protocolo :tipo :titular_identidade_id :detalhe
   :estado :recibo_em :created_by :criado_em :atualizado_em])

(defn protocolar!
  "Protocola: sequencial gapless (escopo 'solicitacao_titular:<ano>' do ente da SESSAO — le ente_id do GUC) +
  protocolo humano (logic) + insert, atomico na tx. `detalhe` OPCIONAL (nil ok). titular/created-by INJETADOS
  do ator UPSTREAM (nunca do corpo). Devolve {:id :ano :sequencial :protocolo :recibo-em}."
  [tx {:keys [id ente-id ano tipo titular-identidade-id detalhe recibo-em created-by]}]
  {:pre [(some? ente-id) (some? id) (some? tipo) (some? recibo-em) (some? titular-identidade-id)]}
  (let [seq-val   (sequencial/proximo! tx (str "solicitacao_titular:" ano))
        protocolo (logic/protocolo-titular ano seq-val)]
    (jdbc/execute-one! tx
      (sql/format {:insert-into :participacao.solicitacao_titular
                   :values [{:id id :ente_id ente-id :ano ano :sequencial seq-val :protocolo protocolo
                             :tipo tipo :titular_identidade_id titular-identidade-id :detalhe detalhe
                             :estado "protocolada" :recibo_em recibo-em
                             :created_by created-by :efetivado_em [:now]}]}))
    {:id id :ano ano :sequencial seq-val :protocolo protocolo :recibo-em recibo-em}))

(defn buscar
  "Busca uma solicitacao por id no tenant (RLS via ente-id). Devolve o mapa kebab-case ou nil."
  [tx ente-id id]
  {:pre [(some? ente-id) (some? id)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select cols :from [:participacao.solicitacao_titular]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn responder!
  "CAS de RESPOSTA: transiciona a solicitacao p/ 'respondida' SOMENTE se ainda esta ABERTA (protocolada|em_analise).
  Pode saltar direto de 'protocolada' (o grafo transicoes-solicitacao-titular admite). Devolve o mapa kebab
  (incl. :protocolo) se transicionou, ou nil se ja estava terminal (respondida|indeferida) — a borda desambigua
  nil-existente p/ 409. A transicao P/ terminal PASSA o trg_solicitacao_titular_trava_terminal (so mexer numa
  linha JA terminal trava)."
  [tx {:keys [id ente-id]}]
  {:pre [(some? ente-id) (some? id)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:update :participacao.solicitacao_titular
                  :set {:estado "respondida" :atualizado_em [:now]}
                  :where [:and [:= :ente_id ente-id] [:= :id id]
                          [:in :estado [[:inline "protocolada"] [:inline "em_analise"]]]]
                  :returning [:*]}))))
