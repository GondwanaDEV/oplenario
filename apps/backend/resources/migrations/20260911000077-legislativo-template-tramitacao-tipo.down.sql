DROP INDEX IF EXISTS legislativo.idx_template_tramitacao_resolucao;
--;;
ALTER TABLE legislativo.template_tramitacao DROP CONSTRAINT IF EXISTS template_tramitacao_tipo_sujeito_ck;
--;;
ALTER TABLE legislativo.template_tramitacao DROP COLUMN IF EXISTS tipo;
