DROP INDEX IF EXISTS legislativo.idx_parecer_juridico_nota;
--;;
ALTER TABLE legislativo.parecer_juridico DROP CONSTRAINT IF EXISTS parecer_juridico_nota_fk;
--;;
ALTER TABLE legislativo.parecer_juridico DROP CONSTRAINT IF EXISTS parecer_juridico_origem_completa;
--;;
ALTER TABLE legislativo.parecer_juridico DROP COLUMN IF EXISTS nota_tecnica_id;
--;;
ALTER TABLE legislativo.parecer_juridico DROP COLUMN IF EXISTS origem_rascunho;
--;;
ALTER TABLE legislativo.pedido_parecer_juridico DROP CONSTRAINT IF EXISTS pedido_parecer_juridico_origem_check;
--;;
ALTER TABLE legislativo.pedido_parecer_juridico
  ADD CONSTRAINT pedido_parecer_juridico_origem_check CHECK (origem IN ('secretaria', 'relator'));
