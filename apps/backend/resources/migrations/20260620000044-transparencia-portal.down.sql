-- 1a migration do modulo transparencia: derruba as tabelas de read-model + o GRANT USAGE do schema.
DROP TABLE IF EXISTS transparencia.materia;
--;;
DROP TABLE IF EXISTS transparencia.norma;
--;;
REVOKE USAGE ON SCHEMA transparencia FROM oplenario_app;
