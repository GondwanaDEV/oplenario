DROP TABLE IF EXISTS sessoes.sessao;
--;;
-- 1a migration do modulo: o down devolve o USAGE concedido no up (remover quando houver migration posterior
-- do schema sessoes — padrao F3.x: so a 1a migration do modulo carrega o REVOKE).
REVOKE USAGE ON SCHEMA sessoes FROM oplenario_app;
