DROP TABLE IF EXISTS sessoes.tempo_regimental;
--;;
ALTER TABLE sessoes.fala_executada DROP CONSTRAINT IF EXISTS fala_tempo_concedido_positivo;
--;;
ALTER TABLE sessoes.fala_executada DROP COLUMN IF EXISTS tempo_concedido_segundos;
