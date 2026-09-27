DROP TABLE IF EXISTS legislativo.recebimento_tramitacao;
--;;
ALTER TABLE legislativo.template_estado DROP COLUMN IF EXISTS recebedor;
--;;
ALTER TABLE legislativo.template_estado DROP COLUMN IF EXISTS exige_recebimento;
