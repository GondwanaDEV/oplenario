-- Onda D Slice 2: sessao opaca de LOGIN (custodia BFF). SUPRATENANT (sem RLS) como identidade/identidade_externa:
-- a resolucao de sessao precede o contexto de tenant. Zero tokens de IdP em repouso: so o hash do segredo do
-- cookie + identidade + ente + prazos. Gated ao oplenario_id_resolver (anti-enumeracao, disc.1 F1.3).
CREATE TABLE IF NOT EXISTS identidade.sessao (
  sessao_hash   bytea PRIMARY KEY,            -- sha256(segredo-opaco-do-cookie); nunca o segredo cru
  identidade_id uuid NOT NULL,                -- ref supratenant a identidade.identidade(id)
  ente_id       uuid NOT NULL,                -- tenant da sessao (do issuer verificado no mint)
  criada_em     timestamptz NOT NULL DEFAULT now(),
  expira_em     timestamptz NOT NULL,         -- teto ABSOLUTO
  ocioso_ate    timestamptz NOT NULL          -- expiracao por OCIOSIDADE (deslizante)
);
--;;
CREATE INDEX IF NOT EXISTS idx_sessao_expira ON identidade.sessao (expira_em);
--;;
-- oplenario_app (role efetivo dentro de com-tenant*) NAO enxerga sessao (mesma disciplina do CPF).
GRANT SELECT, INSERT, UPDATE, DELETE ON identidade.sessao TO oplenario_id_resolver;
