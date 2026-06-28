-- reverte F1.3: dropa as tabelas do identidade (CASCADE leva FKs/policies/indices). O schema pertence a mig 1.
DROP TABLE IF EXISTS identidade.consentimento CASCADE;
--;;
DROP TABLE IF EXISTS identidade.usuario_papel CASCADE;
--;;
DROP TABLE IF EXISTS identidade.vinculo CASCADE;
--;;
DROP TABLE IF EXISTS identidade.identidade_externa CASCADE;
--;;
DROP TABLE IF EXISTS identidade.identidade CASCADE;
--;;
REVOKE USAGE ON SCHEMA identidade FROM oplenario_app;
--;;
REVOKE USAGE ON SCHEMA identidade FROM oplenario_id_resolver;
--;;
REVOKE oplenario_id_resolver FROM oplenario_pool;
--;;
DROP ROLE IF EXISTS oplenario_id_resolver;
