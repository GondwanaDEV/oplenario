(ns oplenario.cadastros.db.estrutura
  "Persistencia da estrutura institucional do tenant: ente (perfil 1:1), legislatura, sessao_legislativa.
  Funcoes sobre a `tx` do tenant (RLS isola). HoneySQL -> next.jdbc, schema-qualified (ADR-0001 §3)."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

;; ---- ente (perfil cadastral 1:1) ----
(defn inserir-ente!
  "Cria/atualiza o perfil cadastral do ente. Idempotente (ON CONFLICT) p/ re-provisionamento seguro (retry)."
  [tx {:keys [ente-id municipio-ibge nome-oficial nome-curto brasao-ref]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :cadastros.ente
                 :values [{:ente_id ente-id :municipio_ibge municipio-ibge :nome_oficial nome-oficial
                           :nome_curto nome-curto :brasao_ref brasao-ref}]
                 :on-conflict [:ente_id]
                 :do-update-set {:municipio_ibge :excluded.municipio_ibge
                                 :nome_oficial   :excluded.nome_oficial
                                 :nome_curto     :excluded.nome_curto
                                 :brasao_ref     :excluded.brasao_ref
                                 :atualizado_em  [:now]}})))

(defn buscar-ente [tx]
  ;; RLS ja restringe ao tenant corrente -> a unica linha visivel e' a da Casa.
  (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [:ente_id :municipio_ibge :nome_oficial :nome_curto :brasao_ref]
                   :from [:cadastros.ente]}))))

;; ---- legislatura ----
(defn inserir-legislatura!
  ;; criacao NATIVA -> nasce efetivada (efetivado_em = now()); o caminho de import (admin_sistema) e' que
  ;; estaga com efetivado_em NULL + lote_id. Sem isto a RLS de staging esconde a linha (fundacao #2).
  [tx {:keys [id ente-id numero ano-inicio ano-fim vigente]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :cadastros.legislatura
                 :values [{:id id :ente_id ente-id :numero numero :ano_inicio ano-inicio
                           :ano_fim ano-fim :vigente (boolean vigente) :efetivado_em [:now]}]})))

(defn buscar-legislatura [tx id]
  (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [:id :ente_id :numero :ano_inicio :ano_fim :vigente]
                   :from [:cadastros.legislatura] :where [:= :id id]}))))

(defn legislatura-vigente [tx]
  (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [:id :ente_id :numero :ano_inicio :ano_fim :vigente]
                   :from [:cadastros.legislatura] :where [:= :vigente true] :limit 1}))))

;; ---- sessao legislativa (1..4 dentro da legislatura) ----
(defn inserir-sessao-legislativa! [tx {:keys [id ente-id legislatura-id numero ano data-inicio data-fim]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :cadastros.sessao_legislativa
                 :values [{:id id :ente_id ente-id :legislatura_id legislatura-id :numero numero
                           :ano ano :data_inicio data-inicio :data_fim data-fim :efetivado_em [:now]}]})))
