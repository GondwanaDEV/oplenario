(ns oplenario.participacao.db.resposta-titular
  "Persistencia de 'participacao.resposta_titular' (F6 Slice 4) — APPEND-ONLY (Inv.10): SO `inserir!` (sem
  UPDATE/DELETE; a mig 0041 nega o grant de UPDATE/DELETE + trg_resposta_titular_append_only, e o caller nunca
  os emite). A resposta cita UMA solicitacao do titular (FK composta same-tenant). respondido-por INJETADO do
  ATOR (o servidor/Encarregado). HoneySQL schema-qualified; ente_id em TODA query. IMPL atras do RepoParticipacao."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(defn inserir!
  "Registra uma resposta a uma solicitacao do titular (append-only). Devolve o mapa kebab (RETURNING *)."
  [tx {:keys [id ente-id solicitacao-id corpo respondido-por respondida-em]}]
  {:pre [(some? ente-id) (some? id) (some? solicitacao-id) (some? corpo) (some? respondido-por)
         (some? respondida-em)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :participacao.resposta_titular
                  :values [{:id id :ente_id ente-id :solicitacao_id solicitacao-id
                            :corpo corpo :respondido_por respondido-por :respondida_em respondida-em
                            :efetivado_em [:now]}]
                  :returning [:*]}))))
