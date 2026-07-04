-- Reverte a mig 0051 (F7 E3). NAO revoga USAGE/GRANT de schema nem toca a role oplenario_app: o schema
-- `paineis` e' anterior a esta migration (mig 0004/0009) e outras tabelas do modulo dependem do USAGE.
DROP TABLE IF EXISTS paineis.sli_sessao;
