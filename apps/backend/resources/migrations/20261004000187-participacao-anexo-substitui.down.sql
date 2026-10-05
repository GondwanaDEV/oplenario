DROP INDEX IF EXISTS participacao.uq_participacao_anexo_substitui;
--;;
ALTER TABLE participacao.anexo DROP CONSTRAINT IF EXISTS anexo_substitui_fk;
--;;
ALTER TABLE participacao.anexo DROP COLUMN IF EXISTS substitui_anexo_id;
