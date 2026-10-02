ALTER TABLE legislativo.parecer_juridico DROP CONSTRAINT IF EXISTS parecer_juridico_carimbo_completo;
--;;
ALTER TABLE legislativo.parecer_juridico
  DROP COLUMN IF EXISTS conteudo_sha256,
  DROP COLUMN IF EXISTS assinatura_b64,
  DROP COLUMN IF EXISTS assinatura_algoritmo;
