DROP TABLE IF EXISTS admin_sistema.pedido_restricao;
--;;
ALTER TABLE admin_sistema.ente DROP CONSTRAINT IF EXISTS ente_motivo_restricao_valido;
--;;
ALTER TABLE admin_sistema.ente
  DROP COLUMN IF EXISTS suspensao_agendada, DROP COLUMN IF EXISTS restrita_desde, DROP COLUMN IF EXISTS motivo_restricao;
