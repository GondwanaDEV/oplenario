(ns oplenario.sessoes.db.anuncio
  "Persistencia do ATO DE ANUNCIAR UM ITEM DA PAUTA (docs/23 Fatia 4b) — funcoes sobre a `tx` do tenant (RLS
  isola). `item_anunciado` e' APPEND-ONLY: registra QUANDO a Mesa passou a apreciar QUAL item e QUEM registrou.
  O 'item em apreciacao' de uma sessao e' o ULTIMO anuncio dela — nao ha' ponteiro mutavel. Ver o cabecalho da
  migration 0080 para o porque de uma tabela propria em vez de um tipo novo em `pauta_alteracao`. HoneySQL
  schema-qualified; ente_id em toda query."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private cols
  [:id :ente_id :sessao_id :pauta_item_id :anunciado_em :created_by :registrado_em])

(defn registrar!
  "Registra o anuncio (append-only). nil-guard de auditoria (`created-by`, trilha de quem registrou). Devolve
  {:id :sessao-id :pauta-item-id :anunciado-em :registrado-em} por RETURNING — o recibo diz o que o banco
  gravou, nunca ecoa o que entrou."
  [tx {:keys [id ente-id sessao-id pauta-item-id anunciado-em created-by]}]
  (when (nil? created-by)
    (throw (ex-info "registrar!: created-by e' obrigatorio (trilha de auditoria)" {:id id})))
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :sessoes.item_anunciado
                  :values [{:id id :ente_id ente-id :sessao_id sessao-id :pauta_item_id pauta-item-id
                            :anunciado_em anunciado-em :created_by created-by :efetivado_em [:now]}]
                  :returning [:id :sessao_id :pauta_item_id :anunciado_em :registrado_em]}))))

(defn ultimo-da-sessao
  "O anuncio MAIS RECENTE da sessao (o item em apreciacao), ou nil. Desempate por `registrado_em` e `id`: dois
  anuncios no mesmo instante de dominio sao raros, mas a leitura tem de ser deterministica (a TV e o cockpit
  nao podem discordar sobre qual item esta em apreciacao). Servido por `idx_item_anunciado_sessao`."
  [tx ente-id sessao-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select cols :from [:sessoes.item_anunciado]
                  :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]]
                  :order-by [[:anunciado_em :desc] [:registrado_em :desc] [:id :desc]]
                  :limit 1}))))
