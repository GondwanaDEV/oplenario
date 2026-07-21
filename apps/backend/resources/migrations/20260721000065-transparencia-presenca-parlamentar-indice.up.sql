-- Onda E fatia 2 fix (revisao Task 2, achado M-4): a migration 0064 criou
-- `idx_presenca_parlamentar_vereador (ente_id, vereador_id)`, mas o UNICO leitor hoje
-- (`transparencia.db.parlamentar/resumo-presenca`) filtra so' por `ente_id` no WHERE — `vereador_id` fica
-- DENTRO do CASE da agregacao (COUNT DISTINCT condicional), nunca na clausula WHERE. O indice criado nunca e'
-- escolhido pelo planner para essa consulta. Migration 0064 e' IMUTAVEL (ja aplicada em ambiente vivo) —
-- corrige-se com migration NOVA: derruba o indice que ninguem usa, sobe o que a consulta usaria.
DROP INDEX IF EXISTS transparencia.idx_presenca_parlamentar_vereador;
--;;
CREATE INDEX IF NOT EXISTS idx_presenca_parlamentar_ente
  ON transparencia.presenca_parlamentar (ente_id);
