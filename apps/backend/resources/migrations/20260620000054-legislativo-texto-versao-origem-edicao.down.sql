ALTER TABLE legislativo.proposicao_texto_versao DROP CONSTRAINT proposicao_texto_versao_origem_versao_check;
--;;
ALTER TABLE legislativo.proposicao_texto_versao ADD CONSTRAINT proposicao_texto_versao_origem_versao_check
  CHECK (origem_versao IN
    ('protocolo','substitutivo','aplicacao_emenda','redacao_final','promulgacao','importacao_legado'));
