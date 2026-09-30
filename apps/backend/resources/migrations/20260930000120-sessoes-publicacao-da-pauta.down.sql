ALTER TABLE sessoes.pauta_sessao_versao DROP COLUMN IF EXISTS avisos;
--;;
ALTER TABLE sessoes.pauta_sessao_versao DROP COLUMN IF EXISTS publicada_a_titulo;
--;;
DROP TABLE IF EXISTS sessoes.regra_pauta;
