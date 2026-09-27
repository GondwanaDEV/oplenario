-- Faixa A / A.8b da Track IA — o RESUMO CIDADAO publicado vai para o read-model publico da materia (o portal). Projecao
-- de `proposicao.resumo-publicado` (legislativo): a versao mais recente, o texto, se partiu da IA e quando foi
-- publicado. Colunas na propria `transparencia.materia` (1:1 com a materia; o historico de versoes fica no legislativo).
ALTER TABLE transparencia.materia ADD COLUMN IF NOT EXISTS resumo_texto text;
--;;
ALTER TABLE transparencia.materia ADD COLUMN IF NOT EXISTS resumo_versao integer;
--;;
ALTER TABLE transparencia.materia ADD COLUMN IF NOT EXISTS resumo_gerado_com_ia boolean;
--;;
ALTER TABLE transparencia.materia ADD COLUMN IF NOT EXISTS resumo_publicado_em timestamptz;
