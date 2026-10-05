-- Simetrico do up. A tabela e' PROJECAO (a verdade das movimentacoes continua em
-- legislativo.proposicao_transicao_historico, que a migration so' lia): derrubar nao perde registro de dominio,
-- nao devolve acesso nem publica nada. Policy e GRANT morrem junto com a tabela. O par NO FORCE/FORCE do up e'
-- auto-reversivel dentro da propria transacao dele, entao nao ha estado a desfazer nas tabelas lidas.
DROP TABLE IF EXISTS transparencia.materia_movimentacao;
