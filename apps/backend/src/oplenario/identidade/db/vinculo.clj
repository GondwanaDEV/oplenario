(ns oplenario.identidade.db.vinculo
  "Persistencia TENANT (FORCE RLS) do identidade: vinculo (identidade<->ente), usuario_papel (RBAC
  estatico) e consentimento (LGPD). Funcoes sobre a `tx` do tenant (com-tenant* -> RLS isola). HoneySQL."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.identidade.models.identidade :as mod]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

;; ---- vinculo ----
(defn criar!
  "Cria o vinculo. Idempotente por (ente_id, identidade_id, tipo) — RETORNA o id CANONICO (o existente, em
  caso de conflito); o caller DEVE usar este id, nao o que passou (mesma disciplina de db/identidade/inserir!).
  DO UPDATE (no-op sobre `tipo`) em vez de DO NOTHING: DO NOTHING nao devolveria RETURNING na colisao."
  [tx {:keys [id ente-id identidade-id tipo estado]}]
  (:vinculo/id
   (jdbc/execute-one! tx
     (sql/format {:insert-into :identidade.vinculo
                  :values [{:id id :ente_id ente-id :identidade_id identidade-id
                            :tipo tipo :estado (or estado "ativo")}]
                  :on-conflict [:ente_id :identidade_id :tipo]
                  :do-update-set {:tipo :excluded.tipo}
                  :returning [:id]}))))

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

(defn estado-de
  "Leitura ESTREITA (so' :estado) do vinculo pelo id. Usada por `conceder-acesso!` (repositorio component,
  Task 12 achado seguranca) pra checar, LOGO apos o UPSERT de `criar!`, se o vinculo canonico segue ativo
  antes de conceder papeis e prosseguir pro Keycloak — `criar!` e' idempotente por (ente,identidade,tipo)
  e o `:do-update-set` de proposito NUNCA toca `:estado` (Task 7), entao re-conceder a um vinculo suspenso
  nao reativa (fail-closed, correto); o que faltava era o CALLER perceber isso antes de mandar convite."
  [tx id]
  (:vinculo/estado
   (jdbc/execute-one! tx
     (sql/format {:select [:estado] :from [:identidade.vinculo] :where [:= :id id]}))))

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
