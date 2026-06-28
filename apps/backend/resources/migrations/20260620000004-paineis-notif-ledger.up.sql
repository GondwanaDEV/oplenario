-- ledger de entrega de notificacao (§22.10 / GAP 4): a UNICA peca DURAVEL no modulo de projecao 'paineis'.
-- e-mail/push que saiu NAO se re-projeta; idempotencia por UNIQUE+CAS (§22.9 Eixo 2) torna redelivery no-op.
-- A *vista* (sininho/inbox) segue projecao dropavel; so a *entrega* mora aqui.
CREATE TABLE IF NOT EXISTS paineis.notificacao_entrega (
  id              uuid PRIMARY KEY,
  ente_id         uuid NOT NULL,                      -- tenant (read-model por ente)
  destinatario    text NOT NULL,                      -- identidade/contato alvo
  canal           text NOT NULL,                      -- email|push
  idempotency_key text NOT NULL,
  estado          text NOT NULL DEFAULT 'pendente',   -- pendente|enviada|falha
  consent_base    text,                               -- consentimento (cidadao) | funcao (servidor/vereador)
  enviada_em      timestamptz,
  criado_em       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (ente_id, idempotency_key)
);
