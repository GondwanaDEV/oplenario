DROP TRIGGER IF EXISTS trg_texto_versao_assinatura_imutavel ON legislativo.proposicao_texto_versao;
--;;
DROP FUNCTION IF EXISTS legislativo.texto_versao_assinatura_imutavel();
--;;
ALTER TABLE legislativo.proposicao_texto_versao DROP CONSTRAINT IF EXISTS texto_versao_assinatura_completa;
--;;
ALTER TABLE legislativo.proposicao_texto_versao
  DROP COLUMN IF EXISTS assinatura_algoritmo,
  DROP COLUMN IF EXISTS assinatura_b64,
  DROP COLUMN IF EXISTS assinado_por,
  DROP COLUMN IF EXISTS assinado_em;
--;;
DELETE FROM legislativo.documento_modelo WHERE tipo_documento = 'requerimento_proposicao';
--;;
ALTER TABLE legislativo.documento_modelo DROP CONSTRAINT IF EXISTS documento_modelo_tipo_documento_check;
--;;
ALTER TABLE legislativo.documento_modelo ADD CONSTRAINT documento_modelo_tipo_documento_check CHECK (
  tipo_documento IN ('oficio', 'certidao', 'requerimento_administrativo', 'convite', 'mala_direta', 'outro'));
