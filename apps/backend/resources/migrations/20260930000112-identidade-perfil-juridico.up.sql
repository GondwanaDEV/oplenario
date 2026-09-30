-- ADR-0019 (Eixo 1) — o perfil do papel `juridico`: a qualificacao (procurador efetivo, assessor comissionado ou
-- advogado contratado) e o numero da OAB do servidor. O parecer juridico grava esse par no ato da assinatura (snapshot),
-- porque precisa dizer a que titulo foi assinado. O PAPEL em si mora em `identidade.usuario_papel` (vocabulario aberto:
-- `juridico` e' so' mais um texto); aqui mora o que o papel carrega. Um perfil por (Casa, pessoa): reconceder atualiza.
-- Tenant (FORCE RLS): o advogado contratado que atende varias Casas tem um perfil em cada uma (a OAB pode diferir).
CREATE TABLE IF NOT EXISTS identidade.perfil_juridico (
  ente_id       uuid NOT NULL,
  identidade_id uuid NOT NULL REFERENCES identidade.identidade (id),
  qualificacao  text NOT NULL CHECK (qualificacao IN ('efetivo', 'comissionado', 'contratado')),
  oab           text NOT NULL CHECK (btrim(oab) <> '' AND length(oab) <= 20),
  criado_em     timestamptz NOT NULL DEFAULT now(),
  atualizado_em timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, identidade_id)
);
--;;
ALTER TABLE identidade.perfil_juridico ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE identidade.perfil_juridico FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON identidade.perfil_juridico;
--;;
CREATE POLICY tenant_isolation ON identidade.perfil_juridico
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE (qualificacao, oab, atualizado_em) ON identidade.perfil_juridico TO oplenario_app;
