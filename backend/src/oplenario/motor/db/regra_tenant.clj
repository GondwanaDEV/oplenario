(ns oplenario.motor.db.regra-tenant
  "Persistencia de 'motor.compliance_regra_tenant' (B2 §22.7.6 — o BINDING por tenant). UNICA tabela
  TENANT do schema 'motor' (FORCE RLS retrofitada em F1.0, migration 0009: policy tenant_isolation por
  app.ente_id). Por isso roda via com-tenant* (o RepoMotor trata) — a `tx` ja tem o GUC. So materializa
  parametro do tenant, opt-out auditado, ou pin de versao. HoneySQL schema-qualified, IMPL atras do RepoMotor."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(defn inserir!
  "Cria/atualiza o binding do ente. Idempotente (ON CONFLICT em (ente_id, template_chave)). A RLS WITH
  CHECK garante ente_id == GUC (a tx ja esta no tenant)."
  [tx {:keys [id ente-id template-chave versao-fixada-id ativa motivo-desativacao parametros-tenant]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :motor.compliance_regra_tenant
                 :values [{:id id :ente_id ente-id :template_chave template-chave
                           :versao_fixada_id versao-fixada-id :ativa (boolean ativa)
                           :motivo_desativacao motivo-desativacao
                           :parametros_tenant (comum/->jsonb (or parametros-tenant {}))}]
                 :on-conflict [:ente_id :template_chave]
                 :do-update-set {:versao_fixada_id   :excluded.versao_fixada_id
                                 :ativa              :excluded.ativa
                                 :motivo_desativacao :excluded.motivo_desativacao
                                 :parametros_tenant  :excluded.parametros_tenant
                                 :atualizado_em      [:now]}})))

(defn por-ente-template
  "O binding do ente para um template_chave (nil = sem binding). parametros_tenant com chaves STRING
  (casa com a chave literal da DSL)."
  [tx ente-id template-chave]
  (when-let [row (jdbc/execute-one! tx
                   (sql/format {:select [:id :ente_id :template_chave :versao_fixada_id :ativa
                                         :motivo_desativacao :parametros_tenant]
                                :from [:motor.compliance_regra_tenant]
                                :where [:and [:= :ente_id ente-id] [:= :template_chave template-chave]]}))]
    (-> (comum/linha->kebab row)
        (update :parametros-tenant comum/jsonb->str))))

(defn ativos-do-ente
  "Os bindings ATIVOS do ente (sweep de re-validacao / resolucao por escopo). parametros com chave STRING."
  [tx ente-id]
  (mapv (fn [row] (-> (comum/linha->kebab row) (update :parametros-tenant comum/jsonb->str)))
        (jdbc/execute! tx
          (sql/format {:select [:id :ente_id :template_chave :versao_fixada_id :ativa :parametros_tenant]
                       :from [:motor.compliance_regra_tenant]
                       :where [:and [:= :ente_id ente-id] [:= :ativa true]]}))))
