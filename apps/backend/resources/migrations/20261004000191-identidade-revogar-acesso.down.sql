-- Desfaz o DDL da revogacao SEM ressuscitar acesso. Antes desta migration o mecanismo era "papel sem linha = sem papel":
-- por isso cada papel REVOGADO vira o que ele ja' era antes — uma linha que nao existe. As colunas so' caem depois disso;
-- derruba-las com a linha revogada ainda la' a faria valer de novo (o acesso tirado voltaria sem ninguem conceder).
-- O que foi revogado deixa de existir como historico (o registro do ato segue na trilha de auditoria).
DROP TRIGGER IF EXISTS papel_revogado_imutavel ON identidade.usuario_papel;
--;;
DROP FUNCTION IF EXISTS identidade.papel_revogado_e_imutavel();
--;;
DELETE FROM identidade.usuario_papel WHERE revogado_em IS NOT NULL;
--;;
-- FAIL-CLOSED: o DELETE acima roda sob RLS forcada; se por qualquer razao nao apagou tudo (papel de execucao sem
-- BYPASSRLS), recusa rodar em vez de derrubar as colunas e reabrir acesso revogado.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM identidade.usuario_papel WHERE revogado_em IS NOT NULL) THEN
    RAISE EXCEPTION 'down recusado: ainda ha papel revogado em identidade.usuario_papel; derrubar as colunas o faria valer de novo'
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
END $$;
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
