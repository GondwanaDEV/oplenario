-- Reverte a mig 0050: remove o indice de pendentes + as colunas de conteudo/rastreabilidade/falha. A tabela
-- `paineis.notificacao_entrega` e suas colunas originais (mig 0004) + RLS/grants (mig 0009) permanecem.
DROP INDEX IF EXISTS paineis.idx_notificacao_pendente;
--;;
ALTER TABLE paineis.notificacao_entrega
  DROP COLUMN IF EXISTS assunto,
  DROP COLUMN IF EXISTS corpo,
  DROP COLUMN IF EXISTS objeto_tipo,
  DROP COLUMN IF EXISTS objeto_id,
  DROP COLUMN IF EXISTS falha_motivo;
