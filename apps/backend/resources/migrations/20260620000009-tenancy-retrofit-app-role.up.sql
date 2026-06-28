-- F1.0: retrofit da tenancy nas tabelas tenant PRE-EXISTENTES (carry CRITICO da review F0.3) +
-- role LOGIN de runtime. As migrations 4 (paineis) e 5 (compliance) adiaram a RLS de proposito
-- ("politica global de tenancy deferida"); agora, ANTES de qualquer dado real, elas herdam o MESMO
-- padrao da exemplar shared.tenancy_prova (FORCE RLS + tenant_isolation + WITH CHECK + grants sem
-- DELETE onde append-only). E o pool de PRODUCAO passa a conectar como role LOGIN NOBYPASSRLS
-- (oplenario_pool), nao mais como superuser — defesa em profundidade (mig/DDL segue como o dono).

-- (1) roles de runtime. oplenario_relay (NOLOGIN): privilegio SO de infra do bus — drena o outbox
-- cross-tenant e mantem o ledger de inbox; NUNCA toca tabela de dominio. oplenario_pool (LOGIN
-- NOBYPASSRLS) = o pool de runtime; membro de oplenario_app E oplenario_relay (herda ambos por INHERIT).
-- Consequencia: DENTRO de com-tenant* (SET LOCAL ROLE oplenario_app) o codigo de dominio NAO tem
-- privilegio de ler o outbox; o relay (conexao crua do pool, role efetivo oplenario_pool) tem.
-- Senha = DEV; PRODUCAO rotaciona via `ALTER ROLE oplenario_pool PASSWORD ...` a partir do segredo no deploy.
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'oplenario_relay') THEN
    CREATE ROLE oplenario_relay NOLOGIN NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'oplenario_pool') THEN
    CREATE ROLE oplenario_pool LOGIN NOBYPASSRLS PASSWORD 'oplenario_dev_pool'
      IN ROLE oplenario_app, oplenario_relay;
  END IF;
END $$;
--;;
-- (2) compliance (mig 5): tabelas TENANT do runtime do motor. USAGE no schema + RLS por tabela.
GRANT USAGE ON SCHEMA compliance TO oplenario_app;
--;;
ALTER TABLE compliance.prazo_dominio_ativo ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE compliance.prazo_dominio_ativo FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON compliance.prazo_dominio_ativo;
--;;
CREATE POLICY tenant_isolation ON compliance.prazo_dominio_ativo
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- obrigacao: estado evolui via UPDATE (pendente->cumprida/vencida...). Sem DELETE (Inv.10).
GRANT SELECT, INSERT, UPDATE ON compliance.prazo_dominio_ativo TO oplenario_app;
--;;
ALTER TABLE compliance.compliance_avaliacao ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE compliance.compliance_avaliacao FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON compliance.compliance_avaliacao;
--;;
CREATE POLICY tenant_isolation ON compliance.compliance_avaliacao
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- compliance_avaliacao = prova de compliance APPEND-ONLY (Inv.10): so SELECT + INSERT (sem UPDATE/DELETE).
GRANT SELECT, INSERT ON compliance.compliance_avaliacao TO oplenario_app;
--;;
ALTER TABLE compliance.remessa_gerada ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE compliance.remessa_gerada FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON compliance.remessa_gerada;
--;;
CREATE POLICY tenant_isolation ON compliance.remessa_gerada
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- artefato imutavel por versao; o ESTADO de submissao evolui via UPDATE (rascunho->...->aceita).
GRANT SELECT, INSERT, UPDATE ON compliance.remessa_gerada TO oplenario_app;
--;;
-- (3) paineis (mig 4): ledger de entrega — read-model DURAVEL por tenant (a unica verdade do modulo de projecao).
GRANT USAGE ON SCHEMA paineis TO oplenario_app;
--;;
ALTER TABLE paineis.notificacao_entrega ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE paineis.notificacao_entrega FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON paineis.notificacao_entrega;
--;;
CREATE POLICY tenant_isolation ON paineis.notificacao_entrega
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- ledger CAS (UNIQUE) + transicoes de estado (pendente->enviada/falha) via UPDATE; sem DELETE.
GRANT SELECT, INSERT, UPDATE ON paineis.notificacao_entrega TO oplenario_app;
--;;
-- (4) motor (mig 6): compliance_regra_tenant e' a UNICA tabela TENANT do schema motor (binding de regra
-- por ente). A §22.7.6 deixou sua RLS "deferida"; fecho-a AQUI, ANTES de qualquer grant de schema que a
-- F2 fara — senao, no instante em que o resolvedor ligar e o motor ganhar USAGE no schema, ela vazaria
-- binding de regra cross-tenant. As tabelas de catalogo (dominio, SEM ente_id) seguem sem grant ao app
-- ate a F2 ler de fato (privilegio minimo): template_compliance, prazo_dominio_vigente, calendario_feriado,
-- registry_catalogo_versao nao recebem grant aqui.
GRANT USAGE ON SCHEMA motor TO oplenario_app;
--;;
ALTER TABLE motor.compliance_regra_tenant ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE motor.compliance_regra_tenant FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON motor.compliance_regra_tenant;
--;;
CREATE POLICY tenant_isolation ON motor.compliance_regra_tenant
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE ON motor.compliance_regra_tenant TO oplenario_app;
--;;
-- (5) INFRA/transporte (mig 2/7): shared.outbox + shared.evento_consumido NAO levam tenant-RLS de
-- PROPOSITO — sao drenadas CROSS-TENANT pelo relay (o relay nao roda em sessao de tenant; ente_id aqui
-- e' DADO de roteamento/observabilidade — Inv.8 —, nao fronteira de isolamento). PRIVILEGIO MINIMO por papel:
--   - dominio (oplenario_app, dentro de com-tenant*): SO INSERT no outbox (emitir! o evento do ato).
--     Sem SELECT/UPDATE -> codigo de dominio NAO le payload de outro tenant nem mexe no ledger de inbox.
--   - relay (oplenario_relay): le/marca o outbox e mantem o ledger de inbox (incl. a poda DELETE).
GRANT INSERT ON shared.outbox TO oplenario_app;
--;;
GRANT USAGE ON SCHEMA shared TO oplenario_relay;
--;;
GRANT SELECT, UPDATE ON shared.outbox TO oplenario_relay;
--;;
GRANT SELECT, INSERT, DELETE ON shared.evento_consumido TO oplenario_relay;
