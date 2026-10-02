-- ADR-0018 (fatia 1): a Casa SUSPENSA tem a cota de IA zerada (Eixo 2 — o que a IA faz sozinha e' custo sem
-- contrato), pelo proprio orcamento da ADR-0014: a suspensao define 0/0 e a reativacao devolve o que valia antes.
-- Quando antes NAO havia orcamento (a Casa so' media), a reativacao precisa dizer isso no historico append-only: uma
-- definicao SEM VALOR (mensal e teto nulos, os dois juntos) = "a Casa volta a so' medir".
ALTER TABLE integracao_ia.orcamento_ia ALTER COLUMN mensal DROP NOT NULL;
--;;
ALTER TABLE integracao_ia.orcamento_ia ALTER COLUMN teto_duro DROP NOT NULL;
--;;
ALTER TABLE integracao_ia.orcamento_ia DROP CONSTRAINT IF EXISTS orcamento_valores_juntos;
--;;
ALTER TABLE integracao_ia.orcamento_ia ADD CONSTRAINT orcamento_valores_juntos
  CHECK ((mensal IS NULL) = (teto_duro IS NULL));
