-- F0.2: o bus outbox->relay->consumer. O dedup do CONSUMO migra p/ um ledger de inbox (§22.9 E2);
-- o shared.outbox vira log de evento puro (1 linha por emit, broadcast por 'tipo'). O UNIQUE inline
-- (consumidor, idempotency_key) era inerte p/ consumidor NULL (NULL != NULL — achado de review F0.1) e
-- o 'consumidor' nao roteia (fan-out e por registro de 'tipo' no relay): ambos saem.
ALTER TABLE shared.outbox DROP CONSTRAINT IF EXISTS outbox_consumidor_idempotency_key_key;
--;;
ALTER TABLE shared.outbox DROP COLUMN IF EXISTS consumidor;
--;;
-- relay poll: index PARCIAL das pendentes. A tabela cresce sem limite (linhas nunca deletadas), mas as
-- pendentes (processed_at IS NULL) sao poucas; sem isto o SELECT ... FOR UPDATE SKIP LOCKED vira Seq Scan.
-- [escala] quando o nº de workers crescer, considerar INCLUDE (ente_id, tipo, idempotency_key) p/ index-only.
CREATE INDEX IF NOT EXISTS idx_outbox_pendente ON shared.outbox (id) WHERE processed_at IS NULL;
--;;
-- ledger de inbox (dedup do consumo, §22.9 E2): CAS por (consumidor, idempotency_key); PK = dedup natural.
-- inserir-no-ledger + tratar + marcar processado na MESMA tx do relay => effectively-once (rollback retenta).
-- ente_id desde ja (custa nada antes de haver dado) p/ poda por tenant / particao futura.
CREATE TABLE IF NOT EXISTS shared.evento_consumido (
  consumidor      text NOT NULL,
  idempotency_key text NOT NULL,
  ente_id         uuid,
  processado_em   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (consumidor, idempotency_key)
);
--;;
-- poda/retencao: o ledger cresce events/dia × consumidores e cada entrada vira peso morto apos processar
-- (idempotency_key e' UUID aleatorio, nunca reaparece). Index no tempo p/ o DELETE de retencao
-- (kernel.outbox/limpar-consumidos!), rodado periodicamente pelo relay lider.
CREATE INDEX IF NOT EXISTS idx_evento_consumido_ts ON shared.evento_consumido (processado_em);
