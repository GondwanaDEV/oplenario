-- Onda B Slice 2 (editor-proposicao): estende origem_versao com 'edicao' — correcao de metadados/texto
-- pelo servidor via PATCH, fora do processo formal de substitutivo/emenda/redacao-final (eixo D tem
-- fluxo proprio; usar 'substitutivo' aqui estaria semanticamente errado). CHECK constraint (nao enum do
-- Postgres); PARTITION BY HASH herda a constraint alterada no pai automaticamente em todas as particoes.
ALTER TABLE legislativo.proposicao_texto_versao DROP CONSTRAINT proposicao_texto_versao_origem_versao_check;
--;;
ALTER TABLE legislativo.proposicao_texto_versao ADD CONSTRAINT proposicao_texto_versao_origem_versao_check
  CHECK (origem_versao IN
    ('protocolo','substitutivo','aplicacao_emenda','redacao_final','promulgacao','importacao_legado','edicao'));
