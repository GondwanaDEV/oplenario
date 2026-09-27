-- Faixa B / B.9 da Track IA (docs/25 Eixo 8.3, ADR-0014) — o ORCAMENTO DE IA de cada Casa: o valor mensal (aviso a
-- 80%; ao estourar, o segundo plano pausa) e o teto duro (acima dele, nem o que a pessoa pede roda — "cota da Casa").
-- Valores do plano da Casa, definidos pelo OPERADOR (`oplenario.main ia-orcamento`), na moeda da tabela de precos do
-- satelite. Historico append-only: a linha mais recente vale; cada definicao vai a IA como `OrcamentoIADefinido` na
-- mesma transacao. Sem linha = a Casa so' mede.
CREATE TABLE IF NOT EXISTS integracao_ia.orcamento_ia (
  ente_id      uuid NOT NULL,
  id           uuid NOT NULL DEFAULT gen_random_uuid(),
  mensal       numeric(14, 4) NOT NULL CHECK (mensal >= 0),
  teto_duro    numeric(14, 4) NOT NULL,
  moeda        text NOT NULL CHECK (moeda ~ '^[A-Z]{3}$'),
  definido_por text NOT NULL,
  definido_em  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  CONSTRAINT orcamento_teto_cobre_o_mensal CHECK (teto_duro >= mensal)
);
--;;
CREATE INDEX IF NOT EXISTS idx_orcamento_ia_atual ON integracao_ia.orcamento_ia (ente_id, definido_em DESC);
--;;
ALTER TABLE integracao_ia.orcamento_ia ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE integracao_ia.orcamento_ia FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON integracao_ia.orcamento_ia;
--;;
CREATE POLICY tenant_isolation ON integracao_ia.orcamento_ia
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT ON integracao_ia.orcamento_ia TO oplenario_app;
--;;
CREATE TRIGGER trg_orcamento_ia_append_only
  BEFORE UPDATE OR DELETE ON integracao_ia.orcamento_ia
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
