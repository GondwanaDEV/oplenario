DROP INDEX IF EXISTS transparencia.idx_presenca_parlamentar_ente;
--;;
CREATE INDEX IF NOT EXISTS idx_presenca_parlamentar_vereador
  ON transparencia.presenca_parlamentar (ente_id, vereador_id);
