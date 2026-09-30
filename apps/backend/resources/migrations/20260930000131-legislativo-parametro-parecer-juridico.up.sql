-- ADR-0019 fatia 2a (Eixo 4) — ANTECIPAR O PORTAL. Por padrao o parecer juridico assinado vai ao portal so' DEPOIS da
-- deliberacao da materia (LAI art. 7 §3). A Casa pode escolher publicar "ao assinar". E' um parametro por Casa, uma linha
-- (o `admin_ente` liga e desliga em /administracao): sem linha vale o padrao (false = so' depois da deliberacao).
-- A consulta avulsa (sem materia) nunca vai ao portal, qualquer que seja o parametro: responde-se por e-SIC.
CREATE TABLE IF NOT EXISTS legislativo.parametro_parecer_juridico (
  ente_id                   uuid NOT NULL,
  publicar_ao_assinar       boolean NOT NULL DEFAULT false,
  atualizado_por            uuid,
  atualizado_em             timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id)
);
--;;
ALTER TABLE legislativo.parametro_parecer_juridico ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.parametro_parecer_juridico FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON legislativo.parametro_parecer_juridico;
--;;
CREATE POLICY tenant_isolation ON legislativo.parametro_parecer_juridico
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE (publicar_ao_assinar, atualizado_por, atualizado_em)
  ON legislativo.parametro_parecer_juridico TO oplenario_app;
