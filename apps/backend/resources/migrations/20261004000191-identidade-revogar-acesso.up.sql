-- ADR-0005 (adendo "Revogar acesso") — o `admin_ente` concede acesso em /administracao e agora tambem o REVOGA. O papel
-- concedido (`identidade.usuario_papel`) ganha o ato de revogacao: quem, quando e por que. Mesmo padrao da concessao do
-- agente institucional (mig 0096): revogar FECHA a linha, conceder de novo ABRE outra — nada e' apagado, e uma pessoa
-- que perdeu o acesso e o recebeu de novo deixa as duas linhas no historico.
--
-- Por isso a unicidade (Casa, pessoa, papel) deixa de valer para a tabela toda e passa a valer so' para os papeis
-- ATIVOS (indice unico parcial): no maximo um ativo por (Casa, pessoa, papel), quantos revogados forem.
ALTER TABLE identidade.usuario_papel
  ADD COLUMN IF NOT EXISTS revogado_em timestamptz,
  ADD COLUMN IF NOT EXISTS revogado_por uuid,
  ADD COLUMN IF NOT EXISTS motivo_revogacao text;
--;;
ALTER TABLE identidade.usuario_papel DROP CONSTRAINT IF EXISTS papel_revogacao_completa;
--;;
-- quem revogou e por que vem junto com o quando; o motivo nao pode ser vazio (a Casa responde pelo que revogou)
ALTER TABLE identidade.usuario_papel ADD CONSTRAINT papel_revogacao_completa CHECK (
  (revogado_em IS NULL AND revogado_por IS NULL AND motivo_revogacao IS NULL)
  OR (revogado_em IS NOT NULL AND revogado_por IS NOT NULL
      AND motivo_revogacao IS NOT NULL AND btrim(motivo_revogacao) <> '' AND length(motivo_revogacao) <= 500));
--;;
ALTER TABLE identidade.usuario_papel DROP CONSTRAINT IF EXISTS usuario_papel_ente_id_identidade_id_papel_key;
--;;
CREATE UNIQUE INDEX IF NOT EXISTS uq_usuario_papel_ativo
  ON identidade.usuario_papel (ente_id, identidade_id, papel) WHERE revogado_em IS NULL;
--;;
-- o dominio so' passa a poder mexer nas colunas da revogacao (antes tinha UPDATE na tabela toda, sem nunca usar)
REVOKE UPDATE ON identidade.usuario_papel FROM oplenario_app;
--;;
GRANT UPDATE (revogado_em, revogado_por, motivo_revogacao) ON identidade.usuario_papel TO oplenario_app;
--;;
-- linha revogada e' historico: nao se reabre nem se reescreve (reconceder abre OUTRA linha)
CREATE OR REPLACE FUNCTION identidade.papel_revogado_e_imutavel() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.revogado_em IS NOT NULL THEN
    RAISE EXCEPTION 'papel revogado e historico: nao se altera (conceda de novo para abrir outra linha)'
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  RETURN NEW;
END $$;
--;;
DROP TRIGGER IF EXISTS papel_revogado_imutavel ON identidade.usuario_papel;
--;;
CREATE TRIGGER papel_revogado_imutavel BEFORE UPDATE ON identidade.usuario_papel
  FOR EACH ROW EXECUTE FUNCTION identidade.papel_revogado_e_imutavel();
