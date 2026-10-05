-- Desfaz o DDL da revogacao. As linhas REVOGADAS saem (a tabela volta a ser "papel concedido agora"): sem isso o
-- UNIQUE de volta reprovaria quem foi revogado e concedido de novo. O que foi revogado deixa de existir como historico.
DROP TRIGGER IF EXISTS papel_revogado_imutavel ON identidade.usuario_papel;
--;;
DROP FUNCTION IF EXISTS identidade.papel_revogado_e_imutavel();
--;;
DELETE FROM identidade.usuario_papel WHERE revogado_em IS NOT NULL;
--;;
DROP INDEX IF EXISTS identidade.uq_usuario_papel_ativo;
--;;
ALTER TABLE identidade.usuario_papel
  ADD CONSTRAINT usuario_papel_ente_id_identidade_id_papel_key UNIQUE (ente_id, identidade_id, papel);
--;;
ALTER TABLE identidade.usuario_papel DROP CONSTRAINT IF EXISTS papel_revogacao_completa;
--;;
REVOKE UPDATE (revogado_em, revogado_por, motivo_revogacao) ON identidade.usuario_papel FROM oplenario_app;
--;;
GRANT UPDATE ON identidade.usuario_papel TO oplenario_app;
--;;
ALTER TABLE identidade.usuario_papel
  DROP COLUMN IF EXISTS revogado_em, DROP COLUMN IF EXISTS revogado_por, DROP COLUMN IF EXISTS motivo_revogacao;
