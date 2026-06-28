-- reverte F1.1: dropa as tabelas do cadastros (CASCADE leva FKs/policies/indices). O schema 'cadastros'
-- em si pertence a migration 1 (create-schemas) — nao e' dropado aqui.
DROP TABLE IF EXISTS cadastros.comissao_membro CASCADE;
--;;
DROP TABLE IF EXISTS cadastros.comissao_cargo CASCADE;
--;;
DROP TABLE IF EXISTS cadastros.comissao CASCADE;
--;;
DROP TABLE IF EXISTS cadastros.suplencia CASCADE;
--;;
DROP TABLE IF EXISTS cadastros.mandato_licenca CASCADE;
--;;
DROP TABLE IF EXISTS cadastros.mandato CASCADE;
--;;
DROP TABLE IF EXISTS cadastros.vereador CASCADE;
--;;
DROP TABLE IF EXISTS cadastros.sessao_legislativa CASCADE;
--;;
DROP TABLE IF EXISTS cadastros.legislatura CASCADE;
--;;
DROP TABLE IF EXISTS cadastros.ente CASCADE;
--;;
DROP TABLE IF EXISTS cadastros.jurisdicao_camara CASCADE;
--;;
DROP TABLE IF EXISTS cadastros.tribunal_de_contas CASCADE;
--;;
DROP TABLE IF EXISTS cadastros.municipios CASCADE;
--;;
REVOKE USAGE ON SCHEMA cadastros FROM oplenario_app;
