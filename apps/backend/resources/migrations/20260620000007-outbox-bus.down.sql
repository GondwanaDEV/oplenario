DROP TABLE IF EXISTS shared.evento_consumido;
--;;
DROP INDEX IF EXISTS shared.idx_outbox_pendente;
--;;
ALTER TABLE shared.outbox ADD COLUMN IF NOT EXISTS consumidor text;
--;;
-- NOTA: restaura o UNIQUE INERTE (consumidor=NULL => NULL != NULL => sem dedup real). Rodar este DOWN
-- reexpoe o bug pre-F0.2; o dedup real do consumo passou p/ shared.evento_consumido.
ALTER TABLE shared.outbox ADD CONSTRAINT outbox_consumidor_idempotency_key_key UNIQUE (consumidor, idempotency_key);
