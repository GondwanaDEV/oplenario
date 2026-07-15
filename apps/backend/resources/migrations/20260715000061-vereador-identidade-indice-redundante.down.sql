-- Simetrico ao up: recria o indice nao-unico derrubado, DDL identica a' original em
-- 20260620000014-hardening-indices.up.sql.
CREATE INDEX IF NOT EXISTS idx_vereador_identidade
  ON cadastros.vereador (ente_id, identidade_id) WHERE identidade_id IS NOT NULL;
