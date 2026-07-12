ALTER TABLE legislativo.parecer_texto_versao
  DROP COLUMN IF EXISTS assinatura_algoritmo,
  DROP COLUMN IF EXISTS assinatura_b64,
  DROP COLUMN IF EXISTS assinado_por,
  DROP COLUMN IF EXISTS assinado_em;
