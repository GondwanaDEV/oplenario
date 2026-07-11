(ns oplenario.legislativo.db.apensacao
  "Persistencia da apensacao (eixo E) — funcoes sobre a `tx` do tenant (RLS isola). HoneySQL no schema
  'legislativo'. `apensar!` insere o FATO; `desapensar!` e' UPDATE em `desapensada_em` (NAO DELETE), com
  CAS por lock_version e guarda WHERE desapensada_em IS NULL (so a ATIVA desapensa); `cadeia` percorre o
  traversal recursivo (CTE cycle-safe). Mudanca de principal e' DOIS atos (desapensar + apensar), nunca
  um UPDATE in-place — o trigger nivel (c) congela os campos fixos."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private colunas
  [:id :ente_id :principal_id :apensada_id :apensada_em :desapensada_em
   :motivo_apensacao :motivo_desapensacao :ato_apensacao_ref :ato_desapensacao_ref :lock_version])

(defn apensar!
  "Apensa `apensada-id` ao principal `principal-id` (mesmo ente), em estado ATIVO. A UNIQUE parcial
  (ente_id,apensada_id WHERE desapensada_em IS NULL) barra apensar uma proposicao ja apensada a algum
  principal ativo; o CHECK barra apensar a si mesma. Devolve {:id}."
  [tx {:keys [id ente-id principal-id apensada-id motivo-apensacao ato-apensacao-ref created-by]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :legislativo.proposicao_apensacao
                 :values [{:id id :ente_id ente-id :principal_id principal-id :apensada_id apensada-id
                           :motivo_apensacao motivo-apensacao :ato_apensacao_ref ato-apensacao-ref
                           :created_by created-by :efetivado_em [:now]}]}))
  {:id id})

(defn buscar [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:legislativo.proposicao_apensacao]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn desapensar!
  "Desapensa (UPDATE, NAO DELETE — o fato historico persiste): seta `desapensada_em`=now() + motivo/ato
  da desapensacao, com CAS por lock_version. WHERE inclui `desapensada_em IS NULL` -> so a apensacao ATIVA
  desapensa (re-desapensar afeta 0 linhas e lanca; o trigger nivel (c) tambem congela). Lanca em conflito
  de versao, inexistente, ou ja-desapensada (0 linhas afetadas)."
  [tx {:keys [id ente-id motivo-desapensacao ato-desapensacao-ref updated-by lock-version]}]
  (let [r (jdbc/execute-one! tx
            (sql/format {:update :legislativo.proposicao_apensacao
                         :set {:desapensada_em [:now] :motivo_desapensacao motivo-desapensacao
                               :ato_desapensacao_ref ato-desapensacao-ref :updated_by updated-by
                               :atualizado_em [:now] :lock_version [:+ :lock_version 1]}
                         :where [:and [:= :ente_id ente-id] [:= :id id]
                                 [:= :lock_version lock-version]
                                 [:= :desapensada_em nil]]}))]
    (when (zero? (:next.jdbc/update-count r 0))
      (throw (ex-info "desapensar!: conflito de lock_version, inexistente, ou ja desapensada"
                      {:id id :lock-version lock-version})))
    r))

(defn apensadas-ativas
  "As apensadas ATIVAS DIRETAS de um principal (nivel 1 da cadeia). Sem `limite`: todas, ASC. Com `limite`
  (review MAJOR fe-9-ficha-materia): teto empurrado ao SQL (`ORDER BY apensada_em DESC LIMIT limite`) — traz
  as N mais RECENTES, revertido a ASC antes de devolver (mesmo contrato de ordem, so' o conjunto muda)."
  ([tx ente-id principal-id] (apensadas-ativas tx ente-id principal-id nil))
  ([tx ente-id principal-id limite]
   (let [base {:select [:id :apensada_id :apensada_em :motivo_apensacao]
               :from [:legislativo.proposicao_apensacao]
               :where [:and [:= :ente_id ente-id] [:= :principal_id principal-id]
                       [:= :desapensada_em nil]]}
         linhas (comum/linhas->kebab
                  (jdbc/execute! tx
                    (sql/format (if limite
                                  (assoc base :order-by [[:apensada_em :desc]] :limit limite)
                                  (assoc base :order-by [[:apensada_em :asc]])))))]
     (if limite (vec (reverse linhas)) linhas))))

(defn cadeia
  "Cadeia de apensacao (traversal recursivo, §22.4 eixo E) a partir do principal: as apensadas ativas e
  recursivamente as apensadas DELAS (cadeia genuina). CYCLE-SAFE via array de caminho visitado — um ciclo
  (que a UNIQUE-ativa por apensada nao impede entre principais distintos) NAO causa loop infinito. Devolve
  [{:principal-id :apensada-id :nivel}] ordenado por nivel. Schema-qualified; ente_id explicito (a RLS
  filtra, mas a coluna entra no WHERE p/ usar o indice e por defesa)."
  [tx ente-id principal-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     ["WITH RECURSIVE cadeia AS (
         SELECT principal_id, apensada_id, 1 AS nivel,
                ARRAY[principal_id, apensada_id] AS caminho
           FROM legislativo.proposicao_apensacao
          WHERE ente_id = ? AND principal_id = ? AND desapensada_em IS NULL
         UNION ALL
         SELECT a.principal_id, a.apensada_id, c.nivel + 1, c.caminho || a.apensada_id
           FROM legislativo.proposicao_apensacao a
           JOIN cadeia c ON a.principal_id = c.apensada_id
          WHERE a.ente_id = ? AND a.desapensada_em IS NULL
            AND NOT a.apensada_id = ANY(c.caminho)
       )
       SELECT principal_id, apensada_id, nivel FROM cadeia ORDER BY nivel, apensada_id"
      ;; params posicionais: 1=ente_id (base) · 2=principal_id (base) · 3=ente_id (membro recursivo).
      ente-id principal-id ente-id])))
