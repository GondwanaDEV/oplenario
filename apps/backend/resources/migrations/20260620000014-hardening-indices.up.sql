-- F3 hardening (auditoria ampla de codebase): indices de hot-path que faltavam.
-- vereador.por-identidade e' a BASE de toda autorizacao por relacao (F2) — filtra (ente_id, identidade_id).
CREATE INDEX IF NOT EXISTS idx_vereador_identidade
  ON cadastros.vereador (ente_id, identidade_id) WHERE identidade_id IS NOT NULL;
--;;
-- identidade.consentimentos-ativos (LGPD: consulta/revogacao pelo titular) filtra (ente_id, identidade_id)
-- WHERE revogado_em IS NULL.
CREATE INDEX IF NOT EXISTS idx_consentimento_identidade
  ON identidade.consentimento (ente_id, identidade_id) WHERE revogado_em IS NULL;
