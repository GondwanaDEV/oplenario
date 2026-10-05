-- as linhas 'ato:%' da linha do tempo e as duas colunas (a tabela de movimentacao e' append-only para o app; o down
-- roda como dono do schema)
DELETE FROM transparencia.materia_movimentacao WHERE etapa_chave LIKE 'ato:%';
--;;
ALTER TABLE transparencia.materia DROP COLUMN IF EXISTS desfecho_em;
--;;
ALTER TABLE transparencia.materia DROP COLUMN IF EXISTS desfecho;
