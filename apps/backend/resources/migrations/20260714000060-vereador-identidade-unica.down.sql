-- Simetrico ao up: recria o indice nao-unico retirado, depois derruba o unico que esta migration criou.
CREATE INDEX IF NOT EXISTS idx_vereador_identidade
  ON cadastros.vereador (ente_id, identidade_id) WHERE identidade_id IS NOT NULL;
--;;
DROP INDEX IF EXISTS cadastros.idx_vereador_identidade_unica;
