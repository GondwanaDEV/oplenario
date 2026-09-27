ALTER TABLE identidade.vinculo DROP COLUMN IF EXISTS primeiro_acesso_em;
--;;
REVOKE INSERT ON cadastros.municipios FROM oplenario_operacao;
--;;
REVOKE ALL ON admin_sistema.atuacao, admin_sistema.ente FROM oplenario_relay;
--;;
ALTER TABLE admin_sistema.ente DROP CONSTRAINT IF EXISTS ente_estado_valido;
--;;
ALTER TABLE admin_sistema.ente
  DROP COLUMN IF EXISTS ativada_em, DROP COLUMN IF EXISTS convite_enviado_em,
  DROP COLUMN IF EXISTS primeiro_admin_email, DROP COLUMN IF EXISTS primeiro_admin_identidade_id,
  DROP COLUMN IF EXISTS provisionada_por, DROP COLUMN IF EXISTS municipio_nome,
  DROP COLUMN IF EXISTS municipio_ibge, DROP COLUMN IF EXISTS nome_curto;
