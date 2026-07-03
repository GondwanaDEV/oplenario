DROP TABLE IF EXISTS participacao.prazo_ativo;
--;;
DROP TABLE IF EXISTS participacao.pedido_esic;
--;;
-- 1a migration do modulo: o down devolve o USAGE concedido no up (remover quando houver migration posterior
-- do schema participacao — padrao sessoes 0026: so a 1a migration do modulo carrega o REVOKE).
REVOKE USAGE ON SCHEMA participacao FROM oplenario_app;
