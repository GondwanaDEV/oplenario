(ns oplenario.participacao.db.prorrogacao
  "Persistencia de 'participacao.prorrogacao' (GENERALIZACAO fast-follow, mig 0042) — APPEND-ONLY (Inv.10):
  SO `inserir!` + selects (sem UPDATE/DELETE; a mig 0042 nega o grant + trg_prorrogacao_append_only). E' o
  REGISTRO/auditoria de cada prorrogacao; a CAS que impede >1 por objeto mora em
  `participacao.db.prazo-ativo/prorrogar!` — esta tabela POLIMORFICA serve qualquer objeto_tipo de prazo,
  nao so ouvidoria (disciplina 5). `prorrogado-por` INJETADO do ATOR. HoneySQL schema-qualified; ente_id em
  TODA query. IMPL atras do RepoParticipacao."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private cols
  [:id :ente_id :objeto_tipo :objeto_id :de_data :para_data :justificativa
   :prorrogado_por :prorrogado_em :criado_em])

(defn inserir!
  "Registra uma prorrogacao (append-only). Devolve o mapa kebab (RETURNING *)."
  [tx {:keys [id ente-id objeto-tipo objeto-id de-data para-data justificativa prorrogado-por prorrogado-em]}]
  {:pre [(some? ente-id) (some? id) (some? objeto-id) (some? de-data) (some? para-data)
         (some? justificativa) (some? prorrogado-por) (some? prorrogado-em)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :participacao.prorrogacao
                  :values [{:id id :ente_id ente-id :objeto_tipo objeto-tipo :objeto_id objeto-id
                            :de_data de-data :para_data para-data :justificativa justificativa
                            :prorrogado_por prorrogado-por :prorrogado_em prorrogado-em
                            :efetivado_em [:now]}]
                  :returning [:*]}))))

(defn listar-do-objeto
  "Historico de prorrogacoes de UM objeto (ente, objeto_tipo, objeto_id), mais recente primeiro."
  [tx ente-id objeto-tipo objeto-id]
  {:pre [(some? ente-id) (some? objeto-id)]}
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols :from [:participacao.prorrogacao]
                  :where [:and [:= :ente_id ente-id] [:= :objeto_tipo objeto-tipo] [:= :objeto_id objeto-id]]
                  :order-by [[:criado_em :desc] [:id :desc]]}))))
