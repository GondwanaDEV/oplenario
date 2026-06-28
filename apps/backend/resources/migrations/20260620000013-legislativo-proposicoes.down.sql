-- reverte F3.1. Dropar o parent particionado leva junto as particoes, indices e triggers; a funcao
-- de identidade-imutavel (do schema legislativo) NAO depende da tabela -> dropar explicitamente.
DROP FUNCTION IF EXISTS legislativo.proposicao_identidade_imutavel();
--;;
DROP TABLE IF EXISTS legislativo.proposicoes CASCADE;
