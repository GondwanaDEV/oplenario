ALTER TABLE integracao_ia.orcamento_ia DROP CONSTRAINT IF EXISTS orcamento_valores_juntos;
--;;
ALTER TABLE integracao_ia.orcamento_ia ALTER COLUMN teto_duro SET NOT NULL;
--;;
ALTER TABLE integracao_ia.orcamento_ia ALTER COLUMN mensal SET NOT NULL;
