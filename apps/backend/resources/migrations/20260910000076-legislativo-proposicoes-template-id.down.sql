DROP INDEX IF EXISTS legislativo.idx_proposicoes_template_id;
--;;
ALTER TABLE legislativo.proposicoes DROP CONSTRAINT IF EXISTS proposicoes_template_fk;
--;;
ALTER TABLE legislativo.proposicoes DROP COLUMN IF EXISTS template_id;
