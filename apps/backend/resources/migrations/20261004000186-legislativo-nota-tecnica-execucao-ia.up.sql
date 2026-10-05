-- Feature 8.4 (Reportar erro) na NOTA TECNICA do agente institucional (ADR-0013, "Id da execucao na IA").
--
-- `execucao_id` guarda o id da CREDENCIAL delegada do agente (vem do `:via`), nao o da execucao no satelite; o
-- "Reportar erro" precisa do segundo. O satelite passa a mandar `execucao-ia` ao registrar a nota e o core o guarda aqui,
-- como CHAVE DE CORRELACAO: nao e' identidade nem autoridade, nenhuma decisao de autorizacao le esta coluna.
--
-- A nota continua insert-only nesta coluna: o GRANT de UPDATE da tabela e' por coluna (so' a decisao da secretaria) e
-- nao a inclui; a coluna entra no INSERT. Nota anterior fica NULL e a tela nao oferece o botao.
ALTER TABLE legislativo.nota_tecnica ADD COLUMN IF NOT EXISTS execucao_ia uuid;
