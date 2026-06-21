-- outbox transacional (§22.9 Eixo 3) — UNICA tabela que cruza modulos (excecao consciente).
CREATE TABLE IF NOT EXISTS shared.outbox (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  ente_id         UUID,
  tipo            TEXT NOT NULL,
  payload         JSONB NOT NULL,
  idempotency_key TEXT NOT NULL,
  consumidor      TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at    TIMESTAMPTZ,
  UNIQUE (consumidor, idempotency_key)
);
-- consumo via SELECT ... FOR UPDATE SKIP LOCKED.
