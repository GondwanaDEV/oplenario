-- F0.3: politica de tenancy (§22.2 + fundacao #2, v1.41) — RLS por ente_id + 3a dimensao de
-- efetivacao + particao hash(ente_id) + numeracao gapless. Materializa a FORMA; as tabelas de
-- dominio reais (cadastros, F1) copiam o padrao da exemplar abaixo — INCLUSIVE FORCE RLS, o
-- DROP POLICY IF EXISTS (idempotencia), os indices e a postura sem-DELETE.
--
-- Role de aplicacao: o DML normal roda como oplenario_app (NOBYPASSRLS, NAO-dono); o pool de producao
-- conecta como um role LOGIN membro de oplenario_app (F1/deploy). DDL/migrations rodam como o dono.
-- DEFESA EM CAMADAS: FORCE RLS (nem o dono nao-superuser bypassa) + NOBYPASSRLS no app + SET LOCAL ROLE no helper.
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'oplenario_app') THEN
    CREATE ROLE oplenario_app NOLOGIN NOBYPASSRLS;
  END IF;
END $$;
--;;
GRANT USAGE ON SCHEMA shared TO oplenario_app;
--;;
CREATE TABLE IF NOT EXISTS shared.tenancy_prova (
  id           uuid        NOT NULL DEFAULT gen_random_uuid(),  -- app gera via kernel/ids (futuro UUIDv7); DEFAULT = rede
  ente_id      uuid        NOT NULL,
  lote_id      uuid,                       -- lote de importacao (NULL = nativo)
  efetivado_em timestamptz,                -- NULL = nao-efetivado (invisivel em sessao normal)
  dado         text,
  PRIMARY KEY (ente_id, id)
) PARTITION BY HASH (ente_id);
--;;
CREATE TABLE IF NOT EXISTS shared.tenancy_prova_p0 PARTITION OF shared.tenancy_prova FOR VALUES WITH (MODULUS 4, REMAINDER 0);
--;;
CREATE TABLE IF NOT EXISTS shared.tenancy_prova_p1 PARTITION OF shared.tenancy_prova FOR VALUES WITH (MODULUS 4, REMAINDER 1);
--;;
CREATE TABLE IF NOT EXISTS shared.tenancy_prova_p2 PARTITION OF shared.tenancy_prova FOR VALUES WITH (MODULUS 4, REMAINDER 2);
--;;
CREATE TABLE IF NOT EXISTS shared.tenancy_prova_p3 PARTITION OF shared.tenancy_prova FOR VALUES WITH (MODULUS 4, REMAINDER 3);
--;;
-- caminho de staging (app.ver_lote): index parcial das nao-efetivadas por (ente_id, lote_id).
CREATE INDEX IF NOT EXISTS idx_tenancy_prova_lote ON shared.tenancy_prova (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE shared.tenancy_prova ENABLE ROW LEVEL SECURITY;
--;;
-- FORCE: nem o DONO (se nao-superuser) bypassa a RLS — sem isto, um pool conectado como dono vazaria tudo.
ALTER TABLE shared.tenancy_prova FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON shared.tenancy_prova;
--;;
-- isolamento: so o tenant (app.ente_id) E (efetivado OU o lote em staging app.ver_lote).
-- NULLIF(...,'') normaliza GUC nunca-setado (NULL) e placeholder ja-tocado ('') p/ NULL => fail-CLOSED sem erro.
CREATE POLICY tenant_isolation ON shared.tenancy_prova
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL
              OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- sem DELETE: postura append-only/soft-delete (Inv.10); F1 concede DELETE so onde justificado (ex.: LGPD erasure).
GRANT SELECT, INSERT, UPDATE ON shared.tenancy_prova TO oplenario_app;
--;;
-- Numeracao canonica gapless por LINHA-CONTADOR (§22.9 Eixo 2). TENANT-scoped: ente_id vem do GUC
-- (NAO do caller -> ninguem bumpa/le o contador de outro ente); RLS isola. So anda ao commitar (sem buraco).
CREATE TABLE IF NOT EXISTS shared.sequencial (
  ente_id uuid   NOT NULL,
  escopo  text   NOT NULL,
  valor   bigint NOT NULL,
  PRIMARY KEY (ente_id, escopo)
);
--;;
ALTER TABLE shared.sequencial ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE shared.sequencial FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON shared.sequencial;
--;;
CREATE POLICY tenant_isolation ON shared.sequencial
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE ON shared.sequencial TO oplenario_app;
