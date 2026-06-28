(ns oplenario.identidade.db.vinculo
  "Persistencia TENANT (FORCE RLS) do identidade: vinculo (identidade<->ente), usuario_papel (RBAC
  estatico) e consentimento (LGPD). Funcoes sobre a `tx` do tenant (com-tenant* -> RLS isola). HoneySQL."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.identidade.models.identidade :as mod]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

;; ---- vinculo ----
(defn criar! [tx {:keys [id ente-id identidade-id tipo estado]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :identidade.vinculo
                 :values [{:id id :ente_id ente-id :identidade_id identidade-id
                           :tipo tipo :estado (or estado "ativo")}]})))

(defn vinculos-de
  "Os vinculos da identidade NESTE ente (RLS ja restringe ao tenant). Base do escopo ativo (§22.5 eixo D)."
  [tx ente-id identidade-id]
  (comum/linhas->kebab
    (jdbc/execute! tx
      (sql/format {:select [:id :ente_id :identidade_id :tipo :estado]
                   :from [:identidade.vinculo]
                   :where [:and [:= :ente_id ente-id] [:= :identidade_id identidade-id]]
                   ;; ORDER BY determinístico: multi-vínculo ativo NÃO pode escolher vínculo ao acaso
                   ;; (o :vinculo-ativo-id vai p/ o audit; o mais antigo = âncora estável).
                   :order-by [[:criado_em :asc] [:id :asc]]}))))

(defn mudar-estado! [tx id estado]
  {:pre [(contains? mod/estados-vinculo estado)]}   ; erro de dominio antes do CHECK do banco virar PSQLException
  (jdbc/execute-one! tx
    (sql/format {:update :identidade.vinculo :set {:estado estado} :where [:= :id id]})))

;; ---- usuario_papel (RBAC estatico) ----
(defn adicionar-papel! [tx {:keys [id ente-id identidade-id papel]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :identidade.usuario_papel
                 :values [{:id id :ente_id ente-id :identidade_id identidade-id :papel papel}]
                 :on-conflict [:ente_id :identidade_id :papel] :do-nothing true})))

(defn papeis-de
  "Conjunto de papeis estaticos da identidade neste ente (o snapshot do token, §22.5.2 eixo D)."
  [tx ente-id identidade-id]
  (set (map :usuario_papel/papel
            (jdbc/execute! tx
              (sql/format {:select [:papel] :from [:identidade.usuario_papel]
                           :where [:and [:= :ente_id ente-id] [:= :identidade_id identidade-id]]})))))

;; ---- consentimento (LGPD, §22.5.2 eixo G) ----
(defn registrar-consentimento! [tx {:keys [id ente-id identidade-id finalidade base-legal versao-termo]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :identidade.consentimento
                 :values [{:id id :ente_id ente-id :identidade_id identidade-id
                           :finalidade finalidade :base_legal base-legal :versao_termo versao-termo}]})))

(defn revogar-consentimento!
  "Revogacao e' ato auditado (set revogado_em); tratamentos com base 'consentimento' cessam, 'obrigacao
  legal' nao. Retorna true se REVOGOU de fato (LGPD: o titular tem direito a confirmacao); false se o
  consentimento nao existe ou ja estava revogado (caller decide se isso e' erro)."
  [tx id]
  (pos? (:next.jdbc/update-count
         (jdbc/execute-one! tx
           (sql/format {:update :identidade.consentimento :set {:revogado_em [:now]}
                        :where [:and [:= :id id] [:is :revogado_em nil]]})))))

(defn consentimentos-ativos [tx ente-id identidade-id]
  (comum/linhas->kebab
    (jdbc/execute! tx
      (sql/format {:select [:id :ente_id :identidade_id :finalidade :base_legal :versao_termo]
                   :from [:identidade.consentimento]
                   :where [:and [:= :ente_id ente-id] [:= :identidade_id identidade-id] [:is :revogado_em nil]]}))))
