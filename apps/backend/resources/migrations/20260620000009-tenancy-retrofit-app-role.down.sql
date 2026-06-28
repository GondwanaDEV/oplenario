-- reverte F1.0: tira a RLS retrofitada, revoga grants, REVOGA USAGE de schema e remove os roles de runtime.
-- (5) infra/transporte
REVOKE SELECT, INSERT, DELETE ON shared.evento_consumido FROM oplenario_relay;
--;;
REVOKE SELECT, UPDATE ON shared.outbox FROM oplenario_relay;
--;;
REVOKE USAGE ON SCHEMA shared FROM oplenario_relay;
--;;
REVOKE INSERT ON shared.outbox FROM oplenario_app;
--;;
-- (4) motor
DROP POLICY IF EXISTS tenant_isolation ON motor.compliance_regra_tenant;
--;;
ALTER TABLE motor.compliance_regra_tenant NO FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE motor.compliance_regra_tenant DISABLE ROW LEVEL SECURITY;
--;;
REVOKE ALL ON motor.compliance_regra_tenant FROM oplenario_app;
--;;
REVOKE USAGE ON SCHEMA motor FROM oplenario_app;
--;;
-- (3) paineis
DROP POLICY IF EXISTS tenant_isolation ON paineis.notificacao_entrega;
--;;
ALTER TABLE paineis.notificacao_entrega NO FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE paineis.notificacao_entrega DISABLE ROW LEVEL SECURITY;
--;;
REVOKE ALL ON paineis.notificacao_entrega FROM oplenario_app;
--;;
REVOKE USAGE ON SCHEMA paineis FROM oplenario_app;
--;;
-- (2) compliance
DROP POLICY IF EXISTS tenant_isolation ON compliance.remessa_gerada;
--;;
ALTER TABLE compliance.remessa_gerada NO FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE compliance.remessa_gerada DISABLE ROW LEVEL SECURITY;
--;;
REVOKE ALL ON compliance.remessa_gerada FROM oplenario_app;
--;;
DROP POLICY IF EXISTS tenant_isolation ON compliance.compliance_avaliacao;
--;;
ALTER TABLE compliance.compliance_avaliacao NO FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE compliance.compliance_avaliacao DISABLE ROW LEVEL SECURITY;
--;;
REVOKE ALL ON compliance.compliance_avaliacao FROM oplenario_app;
--;;
DROP POLICY IF EXISTS tenant_isolation ON compliance.prazo_dominio_ativo;
--;;
ALTER TABLE compliance.prazo_dominio_ativo NO FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE compliance.prazo_dominio_ativo DISABLE ROW LEVEL SECURITY;
--;;
REVOKE ALL ON compliance.prazo_dominio_ativo FROM oplenario_app;
--;;
REVOKE USAGE ON SCHEMA compliance FROM oplenario_app;
--;;
-- (1) roles. DROP ROLE falha se houver sessao ativa como oplenario_pool — o runbook de rollback
-- deve terminar as conexoes (pg_terminate_backend WHERE usename='oplenario_pool') antes.
DROP ROLE IF EXISTS oplenario_pool;
--;;
DROP ROLE IF EXISTS oplenario_relay;
