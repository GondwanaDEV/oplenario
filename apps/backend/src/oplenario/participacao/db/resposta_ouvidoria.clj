(ns oplenario.participacao.db.resposta-ouvidoria
  "Persistencia de 'participacao.resposta_ouvidoria' (FAST-FOLLOW Slice 5) — APPEND-ONLY (Inv.10): SO
  `inserir!` + selects (sem UPDATE/DELETE; a mig 0042 nega o grant + trg_resposta_ouvidoria_append_only, e o
  caller nunca os emite). Registra tanto a RESPOSTA de merito (responder-manifestacao!) quanto a
  JUSTIFICATIVA de arquivamento (arquivar-manifestacao!) — a mesma tabela append-only serve os dois atos
  administrativos sobre a manifestacao (espelha resposta_esic servindo pedido+recurso). respondido-por
  INJETADO do ATOR. HoneySQL schema-qualified; ente_id em TODA query. IMPL atras do RepoParticipacao."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private cols
  [:id :ente_id :manifestacao_id :corpo :respondido_por :respondida_em :criado_em])

(defn inserir!
  "Registra uma resposta/justificativa (append-only). Devolve o mapa kebab (RETURNING *)."
  [tx {:keys [id ente-id manifestacao-id corpo respondido-por respondida-em]}]
  {:pre [(some? ente-id) (some? id) (some? manifestacao-id) (some? corpo)
         (some? respondido-por) (some? respondida-em)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :participacao.resposta_ouvidoria
                  :values [{:id id :ente_id ente-id :manifestacao_id manifestacao-id
                            :corpo corpo :respondido_por respondido-por :respondida_em respondida-em
                            :efetivado_em [:now]}]
                  :returning [:*]}))))

(defn listar-da-manifestacao
  "Respostas/justificativas de UMA manifestacao (ente, manifestacao_id), mais antigas primeiro (ordem
  cronologica da prova)."
  [tx ente-id manifestacao-id]
  {:pre [(some? ente-id) (some? manifestacao-id)]}
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols :from [:participacao.resposta_ouvidoria]
                  :where [:and [:= :ente_id ente-id] [:= :manifestacao_id manifestacao-id]]
                  :order-by [[:respondida_em :asc] [:id :asc]]}))))
