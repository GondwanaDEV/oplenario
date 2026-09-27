DROP TABLE IF EXISTS admin_sistema.atuacao;
--;;
DROP TABLE IF EXISTS admin_sistema.sessao_operador;
--;;
DROP TABLE IF EXISTS admin_sistema.operador;
--;;
REVOKE ALL ON admin_sistema.ente FROM oplenario_operacao;
--;;
REVOKE USAGE ON SCHEMA admin_sistema FROM oplenario_operacao;
