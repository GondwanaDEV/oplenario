-- ADR-0016: o OPERADOR da plataforma (admin interno, §22.5.1) como principal SUPRATENANT de `admin_sistema`.
-- Sem ente_id e sem RLS: o operador nao tem Casa, ele as provisiona. SPLIT DE PRIVILEGIO (mesmo desenho do
-- oplenario_id_resolver): estas tabelas so' existem para o role oplenario_operacao, do qual o pool herda. O
-- dominio (oplenario_app, role efetivo dentro de com-tenant*) NAO as enxerga — codigo de tenant nao le nem
-- escreve o operador (2a dimensao do teste de vazamento: cross-esfera).
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'oplenario_operacao') THEN
    CREATE ROLE oplenario_operacao NOLOGIN NOBYPASSRLS;
  END IF;
END $$;
--;;
GRANT oplenario_operacao TO oplenario_pool;
--;;
GRANT USAGE ON SCHEMA admin_sistema TO oplenario_operacao;
--;;
GRANT SELECT, INSERT, UPDATE ON admin_sistema.ente TO oplenario_operacao;
--;;
-- O principal. RBAC DISJUNTO do usuario_papel de tenant (§22.10): os papeis daqui nunca valem numa Casa e
-- vice-versa. V1 tem um papel so' ('operador'); a lista existe para nao virar coluna nova quando houver outro.
CREATE TABLE IF NOT EXISTS admin_sistema.operador (
  id           uuid PRIMARY KEY,
  email        text NOT NULL,
  nome         text NOT NULL,
  papeis       text[] NOT NULL DEFAULT ARRAY['operador'],
  estado       text NOT NULL DEFAULT 'ativo',
  criado_em    timestamptz NOT NULL DEFAULT now(),
  desligado_em timestamptz,
  CONSTRAINT operador_papeis_conhecidos CHECK (papeis <@ ARRAY['operador']::text[] AND cardinality(papeis) > 0),
  CONSTRAINT operador_estado CHECK (estado IN ('ativo', 'desligado'))
);
--;;
CREATE UNIQUE INDEX IF NOT EXISTS operador_email_unico ON admin_sistema.operador (lower(email));
--;;
GRANT SELECT, INSERT, UPDATE ON admin_sistema.operador TO oplenario_operacao;
--;;
-- Sessao opaca do console (custodia BFF, mesma mecanica de identidade.sessao): so' o sha256 do segredo do cookie.
CREATE TABLE IF NOT EXISTS admin_sistema.sessao_operador (
  sessao_hash bytea PRIMARY KEY,
  operador_id uuid NOT NULL REFERENCES admin_sistema.operador (id),
  criada_em   timestamptz NOT NULL DEFAULT now(),
  expira_em   timestamptz NOT NULL,
  ocioso_ate  timestamptz NOT NULL
);
--;;
CREATE INDEX IF NOT EXISTS sessao_operador_por_operador ON admin_sistema.sessao_operador (operador_id);
--;;
GRANT SELECT, INSERT, UPDATE, DELETE ON admin_sistema.sessao_operador TO oplenario_operacao;
--;;
-- A atuacao do operador (12.5): append-only com selo ENCADEADO (sha256 do selo anterior + o registro). Sem
-- UPDATE/DELETE para ninguem da aplicacao — o role so' insere e le'. `ente_id` e' nulo no que nao e' de uma Casa
-- (ex.: entrar no console); `operador_id` e' nulo no que a plataforma registra sozinha (ex.: a Casa ficou ativa).
CREATE TABLE IF NOT EXISTS admin_sistema.atuacao (
  seq         bigserial PRIMARY KEY,
  id          uuid NOT NULL UNIQUE,
  em          timestamptz NOT NULL DEFAULT now(),
  operador_id uuid REFERENCES admin_sistema.operador (id),
  ente_id     uuid,
  acao        text NOT NULL,
  detalhe     jsonb NOT NULL DEFAULT '{}'::jsonb,
  selo        text NOT NULL
);
--;;
CREATE INDEX IF NOT EXISTS atuacao_por_ente ON admin_sistema.atuacao (ente_id, seq);
--;;
GRANT SELECT, INSERT ON admin_sistema.atuacao TO oplenario_operacao;
--;;
GRANT USAGE ON SEQUENCE admin_sistema.atuacao_seq_seq TO oplenario_operacao;
