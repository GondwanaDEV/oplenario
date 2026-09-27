-- Faixa B / B.2 da Track IA (docs/25 Eixo 3, ADR-0010) — a CREDENCIAL DELEGADA de um agente. O core a emite quando uma
-- pessoa invoca um agente numa tela (5.2): a pessoa e' o sujeito, o agente e' o ator, a execucao e' uma so'. Mesmo
-- molde da sessao opaca de login (mig 0058): SUPRATENANT (a resolucao precede o contexto de tenant), so' o hash do
-- segredo em repouso, gated ao oplenario_id_resolver.
--
-- A credencial NAO guarda papeis: a permissao efetiva e' recalculada a cada chamada (Eixo 3.2) — o que a pessoa pode
-- AGORA (vinculo ativo + papeis, pela mesma resolucao das telas) ∩ o conjunto do publico ∩ as classes concedidas.
-- `identidade_id` NULL = agente institucional da Casa (3.1 b), que nunca recebe a classe 'ato'.
CREATE TABLE IF NOT EXISTS identidade.credencial_agente (
  credencial_hash bytea PRIMARY KEY,              -- sha256(segredo); nunca o segredo cru
  execucao_id     uuid NOT NULL UNIQUE,           -- uma credencial por execucao do agente
  ente_id         uuid NOT NULL,
  identidade_id   uuid,                           -- a pessoa em nome de quem o agente age; NULL = institucional
  agente          text NOT NULL CHECK (agente ~ '^[a-z][a-z0-9-]{2,62}$'),
  publico         text NOT NULL CHECK (publico IN ('secretaria', 'vereador', 'cidadao')),
  classes         text[] NOT NULL CHECK (array_length(classes, 1) > 0
                                         AND classes <@ ARRAY['leitura', 'rascunho', 'ato']::text[]),
  emitida_em      timestamptz NOT NULL DEFAULT now(),
  expira_em       timestamptz NOT NULL,
  revogada_em     timestamptz,
  CONSTRAINT credencial_prazo CHECK (expira_em > emitida_em),
  CONSTRAINT institucional_nunca_ato CHECK (identidade_id IS NOT NULL OR NOT ('ato' = ANY (classes)))
);
--;;
CREATE INDEX IF NOT EXISTS idx_credencial_agente_expira ON identidade.credencial_agente (expira_em);
--;;
GRANT SELECT, INSERT, UPDATE ON identidade.credencial_agente TO oplenario_id_resolver;
