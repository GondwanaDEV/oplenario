DROP INDEX IF EXISTS legislativo.idx_votacoes_pauta_item;
--;;
ALTER TABLE legislativo.votacoes DROP CONSTRAINT IF EXISTS votacao_pauta_item_requer_sessao;
--;;
ALTER TABLE legislativo.votacoes DROP COLUMN IF EXISTS pauta_item_id;
