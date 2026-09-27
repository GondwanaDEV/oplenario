(ns oplenario.legislativo.db.nota-tecnica
  "Persistencia da NOTA TECNICA de conferencia (Faixa B / B.8, mig 0097, ADR-0013) — funcoes sobre a `tx` do tenant
  (RLS isola). O agente institucional grava o rascunho (texto com marcas de citacao + a conferencia de cada uma); a
  secretaria decide (aproveita, com ou sem edicao, ou descarta). Uma nota por proposicao e agente."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private colunas
  [:n.id :n.proposicao_id :n.agente :n.execucao_id :n.texto :n.citacoes :n.paragrafos_sem_fonte :n.incerteza
   :n.motivos_incerteza :n.modelo_llm_id :n.estado :n.texto_final :n.decidida_por :n.decidida_em :n.criada_em
   :p.tipo :p.sequencial :p.ano :p.ementa])

(defn- ->nota [r]
  (some-> r comum/linha->kebab
          (update :citacoes comum/jsonb->kw)
          (update :paragrafos-sem-fonte comum/jsonb->kw)
          (update :motivos-incerteza comum/jsonb->kw)))

(defn- consulta [ente-id & onde]
  {:select colunas
   :from [[:legislativo.nota_tecnica :n]]
   :join [[:legislativo.proposicoes :p] [:and [:= :p.ente_id :n.ente_id] [:= :p.id :n.proposicao_id]]]
   :where (into [:and [:= :n.ente_id ente-id]] onde)})

(defn buscar [tx ente-id id]
  (->nota (jdbc/execute-one! tx (sql/format (consulta ente-id [:= :n.id id])))))

(defn da-proposicao [tx ente-id proposicao-id agente]
  (->nota (jdbc/execute-one! tx (sql/format (consulta ente-id [:= :n.proposicao_id proposicao-id]
                                                     [:= :n.agente agente])))))

(defn listar
  "A fila: as notas no `estado` (ou todas, com nil), as mais antigas primeiro — quem chegou antes e' conferido antes."
  [tx ente-id estado limite]
  (mapv ->nota (jdbc/execute! tx (sql/format (-> (apply consulta ente-id (when estado [[:= :n.estado estado]]))
                                                 (assoc :order-by [[:n.criada_em :asc] [:n.id :asc]]
                                                        :limit limite))))))

(defn- existe-proposicao? [tx ente-id proposicao-id]
  (some? (jdbc/execute-one! tx (sql/format {:select [1] :from [:legislativo.proposicoes]
                                            :where [:and [:= :ente_id ente-id] [:= :id proposicao-id]]}))))

(defn registrar!
  "Grava o rascunho do agente. Proposicao que nao existe nesta Casa -> nil. Ja' ha' nota desta proposicao por este
  agente (o satelite tentou de novo) -> devolve a existente sem mudar nada. Devolve a nota + `:nova?`."
  [tx {:keys [ente-id proposicao-id agente] :as m}]
  (when (existe-proposicao? tx ente-id proposicao-id)
    (let [inserida (jdbc/execute-one! tx
                     (sql/format {:insert-into :legislativo.nota_tecnica
                                  :values [{:ente_id ente-id :proposicao_id proposicao-id :agente agente
                                            :execucao_id (:execucao-id m) :texto (:texto m)
                                            :citacoes (comum/->jsonb (vec (:citacoes m)))
                                            :paragrafos_sem_fonte (comum/->jsonb (vec (:paragrafos-sem-fonte m)))
                                            :incerteza (:incerteza m)
                                            :motivos_incerteza (comum/->jsonb (vec (:motivos-incerteza m)))
                                            :modelo_llm_id (:modelo-llm-id m)}]
                                  :on-conflict [:ente_id :proposicao_id :agente] :do-nothing true
                                  :returning [:id]}))]
      (assoc (da-proposicao tx ente-id proposicao-id agente) :nova? (some? inserida)))))

(defn decidir!
  "A secretaria decide uma nota PENDENTE: 'aproveitada' (com o `texto-final`) ou 'descartada'. Condicional: nota ja'
  decidida (ou de outra Casa) -> nil."
  [tx ente-id id {:keys [estado texto-final decidida-por]}]
  (when (jdbc/execute-one! tx
          (sql/format {:update :legislativo.nota_tecnica
                       :set {:estado estado :texto_final texto-final :decidida_por decidida-por :decidida_em [:now]}
                       :where [:and [:= :ente_id ente-id] [:= :id id] [:= :estado "pendente"]]
                       :returning [:id]}))
    (buscar tx ente-id id)))
