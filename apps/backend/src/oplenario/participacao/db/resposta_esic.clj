(ns oplenario.participacao.db.resposta-esic
  "Persistencia de 'participacao.resposta_esic' (F6 Slice 2) — APPEND-ONLY (Inv.10): SO `inserir!` + selects
  (sem UPDATE/DELETE; a mig 0040 nega o grant de UPDATE/DELETE + trg_resposta_esic_append_only, e o caller
  nunca os emite). A resposta cita EXATAMENTE UM alvo — um pedido (respondido) OU um recurso (decidido); a
  CHECK resposta_alvo_exclusivo (num_nonnulls=1) e o guarda {:pre} espelham. respondido-por INJETADO do ATOR.
  HoneySQL schema-qualified; ente_id em TODA query. IMPL atras do RepoParticipacao."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private cols
  [:id :ente_id :pedido_id :recurso_id :corpo :respondido_por :respondida_em :criado_em])

(defn inserir!
  "Registra uma resposta (append-only). EXATAMENTE UM de pedido-id/recurso-id nao-nulo (guarda {:pre} espelha
  a CHECK resposta_alvo_exclusivo). Devolve o mapa kebab (RETURNING *)."
  [tx {:keys [id ente-id pedido-id recurso-id corpo respondido-por respondida-em]}]
  {:pre [(some? ente-id) (some? id) (some? corpo) (some? respondido-por) (some? respondida-em)
         (= 1 (count (filter some? [pedido-id recurso-id])))]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :participacao.resposta_esic
                  :values [{:id id :ente_id ente-id :pedido_id pedido-id :recurso_id recurso-id
                            :corpo corpo :respondido_por respondido-por :respondida_em respondida-em
                            :efetivado_em [:now]}]
                  :returning [:*]}))))

(defn listar-do-pedido
  "Respostas de UM pedido (ente, pedido_id), mais antigas primeiro (ordem cronologica da prova)."
  [tx ente-id pedido-id]
  {:pre [(some? ente-id) (some? pedido-id)]}
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols :from [:participacao.resposta_esic]
                  :where [:and [:= :ente_id ente-id] [:= :pedido_id pedido-id]]
                  :order-by [[:respondida_em :asc] [:id :asc]]}))))

(defn listar-do-recurso
  "Respostas (decisoes) de UM recurso (ente, recurso_id), mais antigas primeiro."
  [tx ente-id recurso-id]
  {:pre [(some? ente-id) (some? recurso-id)]}
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols :from [:participacao.resposta_esic]
                  :where [:and [:= :ente_id ente-id] [:= :recurso_id recurso-id]]
                  :order-by [[:respondida_em :asc] [:id :asc]]}))))
