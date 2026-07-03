(ns oplenario.participacao.db.moderacao-comentario
  "Persistencia de 'participacao.moderacao_comentario' (FAST-FOLLOW Slice 6) — APPEND-ONLY (Inv.10): SO
  `inserir!` + selects (sem UPDATE/DELETE; a mig 0043 nega o grant + trg_moderacao_comentario_append_only).
  Registra CADA decisao de moderacao (a trilha exigida pelo roadmap). `moderado-por` INJETADO do ATOR.
  HoneySQL schema-qualified; ente_id em TODA query. IMPL atras do RepoParticipacao."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private cols
  [:id :ente_id :comentario_id :acao :motivo_rejeicao :moderado_por :moderado_em :criado_em])

(defn inserir!
  "Registra uma decisao de moderacao (append-only). Devolve o mapa kebab (RETURNING *)."
  [tx {:keys [id ente-id comentario-id acao motivo-rejeicao moderado-por moderado-em]}]
  {:pre [(some? ente-id) (some? id) (some? comentario-id) (some? acao)
         (some? moderado-por) (some? moderado-em)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :participacao.moderacao_comentario
                  :values [{:id id :ente_id ente-id :comentario_id comentario-id :acao acao
                            :motivo_rejeicao motivo-rejeicao :moderado_por moderado-por
                            :moderado_em moderado-em :efetivado_em [:now]}]
                  :returning [:*]}))))

(defn listar-do-comentario
  "Trilha de moderacao de UM comentario (ente, comentario_id), mais recente primeiro."
  [tx ente-id comentario-id]
  {:pre [(some? ente-id) (some? comentario-id)]}
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols :from [:participacao.moderacao_comentario]
                  :where [:and [:= :ente_id ente-id] [:= :comentario_id comentario-id]]
                  :order-by [[:moderado_em :desc] [:id :desc]]}))))
