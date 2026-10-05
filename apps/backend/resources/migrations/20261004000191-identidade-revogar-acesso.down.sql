-- Desfaz o DDL da revogacao SEM ressuscitar acesso. Antes desta migration o mecanismo era "papel sem linha = sem papel":
-- por isso cada papel REVOGADO vira o que ele ja' era antes — uma linha que nao existe. As colunas so' caem depois disso;
-- derruba-las com a linha revogada ainda la' a faria valer de novo (o acesso tirado voltaria sem ninguem conceder).
-- O que foi revogado deixa de existir como historico (o registro do ato segue na trilha de auditoria).
DROP TRIGGER IF EXISTS papel_revogado_imutavel ON identidade.usuario_papel;
--;;
DROP FUNCTION IF EXISTS identidade.papel_revogado_e_imutavel();
--;;
-- `usuario_papel` tem FORCE ROW LEVEL SECURITY e a policy compara `ente_id` com o GUC `app.ente_id`, que uma migration
-- nao seta: com FORCE ligado o DELETE apagaria ZERO linha e a conferencia abaixo tambem leria ZERO — passaria cega, e as
-- colunas cairiam com a linha revogada ainda la' (acesso de volta). O par NO FORCE/FORCE (mesmo cuidado das migs 0067 e
-- 0182) faz o dono da tabela enxergar todas as Casas; o migratus roda numa transacao, entao falha no meio nao deixa o
-- FORCE desligado.
ALTER TABLE identidade.usuario_papel NO FORCE ROW LEVEL SECURITY;
--;;
DELETE FROM identidade.usuario_papel WHERE revogado_em IS NOT NULL;
--;;
-- FAIL-CLOSED: se ainda assim sobrou papel revogado visivel, ou se quem roda NAO enxerga a tabela inteira (nao e' o
-- dono nem tem BYPASSRLS, entao a conferencia nao prova nada), recusa em vez de derrubar as colunas.
DO $$
BEGIN
  IF NOT (pg_has_role(current_user, (SELECT tableowner FROM pg_tables
                                      WHERE schemaname = 'identidade' AND tablename = 'usuario_papel'), 'MEMBER')
          OR (SELECT rolbypassrls OR rolsuper FROM pg_roles WHERE rolname = current_user)) THEN
    RAISE EXCEPTION 'down recusado: % nao enxerga identidade.usuario_papel inteira (RLS); nao da para provar que nao sobrou papel revogado', current_user
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF EXISTS (SELECT 1 FROM identidade.usuario_papel WHERE revogado_em IS NOT NULL) THEN
    RAISE EXCEPTION 'down recusado: ainda ha papel revogado em identidade.usuario_papel; derrubar as colunas o faria valer de novo'
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
END $$;
--;;
ALTER TABLE identidade.usuario_papel FORCE ROW LEVEL SECURITY;
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
