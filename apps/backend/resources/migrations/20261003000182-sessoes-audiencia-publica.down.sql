DROP TABLE IF EXISTS sessoes.inscricao_cidadao;
--;;
DROP TABLE IF EXISTS sessoes.audiencia;
--;;
ALTER TABLE sessoes.sessao DROP CONSTRAINT IF EXISTS sessao_audiencia_nao_delibera;
--;;
ALTER TABLE sessoes.sessao DROP COLUMN IF EXISTS aceita_inscricao_cidadao;
--;;
ALTER TABLE sessoes.sessao DROP COLUMN IF EXISTS exige_quorum;
--;;
-- falha (de proposito) se ainda houver sessao de audiencia: o down nao apaga sessao da Casa
ALTER TABLE sessoes.sessao DROP CONSTRAINT IF EXISTS sessao_tipo_sessao_check;
--;;
ALTER TABLE sessoes.sessao ADD CONSTRAINT sessao_tipo_sessao_check CHECK (tipo_sessao IN
  ('ordinaria', 'extraordinaria', 'solene', 'secreta', 'especial'));
