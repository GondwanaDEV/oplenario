-- ordem por FK: resposta_ouvidoria (FK -> manifestacao_ouvidoria) antes de manifestacao_ouvidoria;
-- prorrogacao independente (polimorfica, sem FK).
DROP TABLE IF EXISTS participacao.resposta_ouvidoria;
--;;
DROP TABLE IF EXISTS participacao.manifestacao_ouvidoria;
--;;
DROP TABLE IF EXISTS participacao.prorrogacao;
--;;
-- reverte o indice de sweep p/ a forma da mig 0039 (review db: sem isto, o DOWN deixaria o sweep sem
-- indice utilizavel apos a ALTER CONSTRAINT abaixo remover manifestacao_ouvidoria do dominio).
DROP INDEX IF EXISTS participacao.idx_prazo_ativo_sweep_efetivo;
--;;
CREATE INDEX IF NOT EXISTS idx_prazo_ativo_sweep
  ON participacao.prazo_ativo (ente_id, vence_em)
  WHERE estado IN ('pendente', 'vencida');
--;;
-- reverte a CHECK de coerencia de prorrogado_ate (introduzida nesta mesma migration).
ALTER TABLE participacao.prazo_ativo DROP CONSTRAINT prazo_ativo_prorrogado_coerente;
--;;
-- orfaos: sem isto, o ADD CONSTRAINT abaixo falharia (loud, nao silencioso) se restarem prazos de
-- manifestacao_ouvidoria; e mesmo quando nao falha, deixaria linhas referenciando um objeto_tipo que
-- o dominio (pos-down) nao reconhece mais (review db, achado #3).
DELETE FROM participacao.prazo_ativo WHERE objeto_tipo = 'manifestacao_ouvidoria';
--;;
-- devolve o CHECK de objeto_tipo ao estado da mig 0039 (sem manifestacao_ouvidoria).
ALTER TABLE participacao.prazo_ativo DROP CONSTRAINT prazo_ativo_objeto_tipo_check;
--;;
ALTER TABLE participacao.prazo_ativo ADD CONSTRAINT prazo_ativo_objeto_tipo_check CHECK (objeto_tipo IN
  ('pedido_esic', 'recurso_esic', 'solicitacao_titular'));
-- NAO ha REVOKE USAGE: a 0039 (1a migration do modulo) e' quem carrega o GRANT/REVOKE USAGE do schema.
