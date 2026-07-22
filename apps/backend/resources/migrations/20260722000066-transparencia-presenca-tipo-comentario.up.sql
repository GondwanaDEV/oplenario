-- Revisao da fatia 1 do carry I-5. A mig 0064 declarou a coluna como
--   `tipo text NOT NULL,  -- presente|ausente|... (vocabulario de sessoes)`
-- e esse comentario e' a FONTE DOCUMENTAL do bug que a fatia 1 corrigiu: 'presente' e 'ausente' NAO existem
-- em `sessoes.logic/tipos-evento-presenca` (entrada|saida|retorno|mudanca_modalidade, espelhando o CHECK da
-- mig 0029), a coluna aqui NAO tem CHECK, e o consumer copia `:tipo` cru. Quem abrisse a DDL para descobrir o
-- dominio da coluna leria o vocabulario ficticio e escreveria `WHERE tipo = 'presente'` de novo — o mesmo
-- predicado letra-morta, num numero PUBLICO e NOMINAL.
-- Migrations aplicadas sao IMUTAVEIS (a 0064 ja' rodou em ambiente vivo) — corrige-se com arquivo NOVO
-- (precedente literal em 20260721000065:5-6). O COMMENT passa a viver no CATALOGO, nao so' no .sql.
COMMENT ON COLUMN transparencia.presenca_parlamentar.tipo IS
  'Vocabulario de sessoes.logic/tipos-evento-presenca: entrada|saida|retorno|mudanca_modalidade. NUNCA presente/ausente — ausencia e a AUSENCIA de linha (sessoes/relacoes/presenca: "sem evento ate la = ausente"), e por isso o numerador de resumo-presenca NAO filtra por tipo: ter linha == compareceu.';
