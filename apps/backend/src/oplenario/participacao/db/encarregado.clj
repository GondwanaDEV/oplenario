(ns oplenario.participacao.db.encarregado
  "Persistencia de 'participacao.encarregado' (F6 Slice 4, LGPD art. 41 — contato PUBLICO do DPO) — funcoes
  sobre a `tx` do tenant (FORCE RLS isola, mig 0041). CONFIG-like, UM por ente: `upsert!` via INSERT ... ON
  CONFLICT (ente_id) DO UPDATE (a UNIQUE(ente_id) da mig 0041 e' o alvo — garante 1 linha por ente). MUTAVEL
  (nao append-only): a Casa troca de Encarregado; o contato atualiza (SEM DELETE, Inv.10). HoneySQL schema-
  qualified; ente_id em TODA query. IMPL atras do RepoParticipacao."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private cols
  [:id :ente_id :nome :rotulo :email :atualizado_por :criado_em :atualizado_em])

(defn upsert!
  "Define/atualiza o contato do Encarregado do ente (1 por ente). INSERT ... ON CONFLICT (ente_id) DO UPDATE:
  a 1a vez cria; as seguintes ATUALIZAM a MESMA linha (nome/rotulo/email/atualizado_por + carimbo). Devolve o
  mapa kebab (RETURNING *). atualizado-por INJETADO do ator (o servidor que definiu). O `id` estavel apos criado."
  [tx {:keys [id ente-id nome rotulo email atualizado-por]}]
  {:pre [(some? ente-id) (some? id) (some? nome) (some? rotulo) (some? email) (some? atualizado-por)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :participacao.encarregado
                  :values [{:id id :ente_id ente-id :nome nome :rotulo rotulo :email email
                            :atualizado_por atualizado-por :created_by atualizado-por :efetivado_em [:now]}]
                  :on-conflict [:ente_id]
                  :do-update-set {:nome :EXCLUDED.nome :rotulo :EXCLUDED.rotulo :email :EXCLUDED.email
                                  :atualizado_por :EXCLUDED.atualizado_por :atualizado_em [:now]}
                  :returning [:*]}))))

(defn buscar
  "Busca o contato do Encarregado do ente (RLS via ente-id). Devolve o mapa kebab-case ou nil (ente sem DPO definido)."
  [tx ente-id]
  {:pre [(some? ente-id)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select cols :from [:participacao.encarregado]
                  :where [:= :ente_id ente-id]}))))
