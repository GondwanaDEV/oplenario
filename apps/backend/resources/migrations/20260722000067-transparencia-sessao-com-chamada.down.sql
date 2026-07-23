-- Simetrico do up, em ordem inversa. NAO toca `transparencia.presenca_parlamentar`: o par
-- NO FORCE / FORCE do up e' auto-reversivel DENTRO da propria tx do up (a tabela volta a FORCE antes do
-- commit), entao nao ha estado a desfazer aqui. A policy e o GRANT morrem junto com a tabela.
DROP INDEX IF EXISTS transparencia.idx_sessao_com_chamada_data;
--;;
DROP TABLE IF EXISTS transparencia.sessao_com_chamada;
