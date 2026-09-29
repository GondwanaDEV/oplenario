DROP TABLE IF EXISTS auditoria.selo_diario;
--;;
DROP TABLE IF EXISTS auditoria.registro;
--;;
DROP FUNCTION IF EXISTS auditoria.garantir_particoes(timestamptz, integer);
--;;
DROP FUNCTION IF EXISTS auditoria.imut_registro();
--;;
DROP SCHEMA IF EXISTS auditoria;
