-- `ANALYZE` nao tem inverso: ele so' recalcula estatisticas do planner, nao muda schema nem dado. Desfazer
-- seria `DELETE FROM pg_statistic`, que e' pior que o problema. DOWN e' no-op DECLARADO (mesmo racional dos
-- `.down.sql` de COMMENT deste schema): a migration e' idempotente e reversivel por vacuidade.
SELECT 1;
