-- ordem: a filha (FK -> autografo) primeiro. Triggers caem com as tabelas; os helpers shared.imut_* sao
-- compartilhados (nao se removem aqui).
DROP TABLE IF EXISTS legislativo.tramitacao_executiva;
--;;
DROP TABLE IF EXISTS legislativo.autografo;
