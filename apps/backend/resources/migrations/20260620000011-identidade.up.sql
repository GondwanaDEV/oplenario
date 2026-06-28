-- F1.3: modulo identidade (§22.2; silhueta §22.10). Schema 'identidade'. Materializa a disciplina-mae
-- §22.5.3 disc.1: IDENTIDADE (ancora em CPF) e' SUPRATENANT; VINCULO (a relacao identidade<->ente) e' TENANT.
--   SUPRATENANT (sem ente_id, sem RLS — como admin_sistema): identidade, identidade_externa. O acesso a
--     PII (CPF) e' gated em app (controllers/policy.check, F1.4), nao por RLS — uma identidade atravessa
--     todos os seus vinculos (auditoria limpa por identidade_id; §22.5.2 eixo D permite ao audit/admin).
--   TENANT (ente_id, FORCE RLS): vinculo, usuario_papel, consentimento — o escopo de isolamento.
-- FK vinculo->identidade e' INTRA-schema (permitida); refs cross-modulo a identidade cruzam por guard (§22.10).

GRANT USAGE ON SCHEMA identidade TO oplenario_app;
--;;
-- ============================ SUPRATENANT ============================
-- identidade: ancora em CPF (disc.1). UNIQUE(cpf) = uma identidade por CPF, atravessa entes.
CREATE TABLE IF NOT EXISTS identidade.identidade (
  id        uuid PRIMARY KEY,
  cpf       text NOT NULL UNIQUE,                  -- 11 digitos (validacao em app/adapter)
  nome      text NOT NULL,
  criado_em timestamptz NOT NULL DEFAULT now()
);
--;;
-- SPLIT DE PRIVILEGIO (review F1.3 MAJOR-1): as tabelas SUPRATENANT (CPF!) NAO sao acessiveis ao role de
-- dominio (oplenario_app). Senao, codigo de tenant dentro de com-tenant* (role efetivo oplenario_app)
-- poderia ENUMERAR CPF de qualquer ente (sem RLS aqui). Em vez disso, um role dedicado de resolucao de
-- identidade (oplenario_id_resolver, NOLOGIN), do qual oplenario_pool herda — as fns supratenant rodam
-- em conexao CRUA do pool (role efetivo oplenario_pool, herda id_resolver); o dominio (oplenario_app)
-- NAO. Mesmo padrao do split do outbox (F1.0). O check de FK vinculo->identidade NAO exige SELECT (o PG
-- valida FK com privilegio interno), entao o dominio segue inserindo vinculo sem ler a identidade.
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'oplenario_id_resolver') THEN
    CREATE ROLE oplenario_id_resolver NOLOGIN NOBYPASSRLS;
  END IF;
END $$;
--;;
GRANT oplenario_id_resolver TO oplenario_pool;
--;;
GRANT USAGE ON SCHEMA identidade TO oplenario_id_resolver;
--;;
GRANT SELECT, INSERT, UPDATE ON identidade.identidade TO oplenario_id_resolver;
--;;
-- identidade_externa: o broker gov.br (cidadao, disc. eixo D). UNIQUE(provedor, sub) = um sub externo
-- aponta p/ UMA identidade. Vincula a IDENTIDADE (nao ao vinculo) — um CPF gov.br serve a varios vinculos.
CREATE TABLE IF NOT EXISTS identidade.identidade_externa (
  id            uuid PRIMARY KEY,
  identidade_id uuid NOT NULL REFERENCES identidade.identidade (id),
  provedor      text NOT NULL,                     -- 'gov_br' (V1)
  sub           text NOT NULL,                     -- subject do OIDC do provedor
  vinculado_em  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (provedor, sub)
);
--;;
CREATE INDEX IF NOT EXISTS idx_identidade_externa_identidade ON identidade.identidade_externa (identidade_id);
--;;
GRANT SELECT, INSERT, UPDATE ON identidade.identidade_externa TO oplenario_id_resolver;
--;;
-- ============================ TENANT (FORCE RLS) ============================
-- vinculo: a relacao identidade<->ente (servidor/vereador/admin_ente/cidadao). Escopo ativo de sessao
-- (§22.5.2 eixo D) sai de UM vinculo. Auth-critico -> SEM dimensao de staging (nasce vigente, visivel ja).
CREATE TABLE IF NOT EXISTS identidade.vinculo (
  ente_id       uuid NOT NULL,
  id            uuid NOT NULL DEFAULT gen_random_uuid(),
  identidade_id uuid NOT NULL REFERENCES identidade.identidade (id),
  tipo          text NOT NULL CHECK (tipo IN ('servidor','vereador','admin_ente','cidadao')),
  estado        text NOT NULL DEFAULT 'ativo' CHECK (estado IN ('ativo','suspenso','encerrado')),
  criado_em     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  UNIQUE (ente_id, identidade_id, tipo)
);
--;;
CREATE INDEX IF NOT EXISTS idx_vinculo_identidade ON identidade.vinculo (ente_id, identidade_id);
--;;
-- usuario_papel: RBAC estatico centrado em pessoa (§22.5.3 disc.2). papel = texto (vocabulario extensivel:
-- servidor_protocolo, admin_ente, presidente_mesa, ...). Papeis contextuais a recurso moram no recurso.
CREATE TABLE IF NOT EXISTS identidade.usuario_papel (
  ente_id       uuid NOT NULL,
  id            uuid NOT NULL DEFAULT gen_random_uuid(),
  identidade_id uuid NOT NULL REFERENCES identidade.identidade (id),
  papel         text NOT NULL,
  criado_em     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  UNIQUE (ente_id, identidade_id, papel)
);
--;;
-- consentimento: LGPD (§22.5.2 eixo G). base 'consentimento' cessa em revogacao; base 'obrigacao legal' nao.
CREATE TABLE IF NOT EXISTS identidade.consentimento (
  ente_id       uuid NOT NULL,
  id            uuid NOT NULL DEFAULT gen_random_uuid(),
  identidade_id uuid NOT NULL REFERENCES identidade.identidade (id),
  finalidade    text NOT NULL,
  base_legal    text NOT NULL,
  versao_termo  text,
  concedido_em  timestamptz NOT NULL DEFAULT now(),
  revogado_em   timestamptz,
  PRIMARY KEY (ente_id, id)
);
--;;
-- RLS + grants nas 3 tabelas tenant (RLS so por ente_id — sem clausula de staging; dado de auth nasce vigente).
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['vinculo','usuario_papel','consentimento']
  LOOP
    EXECUTE format('ALTER TABLE identidade.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE identidade.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON identidade.%I', t);
    EXECUTE format(
      'CREATE POLICY tenant_isolation ON identidade.%I
         USING (ente_id = NULLIF(current_setting(''app.ente_id'', true), '''')::uuid)
         WITH CHECK (ente_id = NULLIF(current_setting(''app.ente_id'', true), '''')::uuid)', t);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON identidade.%I TO oplenario_app', t);
  END LOOP;
END $$;
