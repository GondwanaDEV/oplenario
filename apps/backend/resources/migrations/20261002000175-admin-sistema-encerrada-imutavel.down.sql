DROP TRIGGER IF EXISTS trg_exportacao_confirmada_imutavel ON admin_sistema.exportacao_casa;
--;;
DROP FUNCTION IF EXISTS admin_sistema.imut_exportacao_confirmada();
--;;
ALTER TABLE admin_sistema.ente DROP CONSTRAINT IF EXISTS ente_encerrada_em_coerente;
--;;
DROP TRIGGER IF EXISTS trg_ente_encerrado_imutavel ON admin_sistema.ente;
--;;
DROP FUNCTION IF EXISTS admin_sistema.imut_ente_encerrado();
