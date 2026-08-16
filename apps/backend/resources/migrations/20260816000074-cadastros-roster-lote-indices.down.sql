-- Simetrico do up, em ordem inversa: recria os dois indices `(ente_id, vereador_id)` que o up limpou e
-- derruba os dois `(ente_id, vereador_id, vigencia_inicio DESC)` que ele criou. Recriar os antigos e' o
-- que mantem o rollback FIEL ao estado da mig 0010 — eles sao redundantes com os novos, mas um down que
-- nao os recria deixaria o banco num estado que nenhuma sequencia de migrations produz (e o roster em lote
-- voltaria a varrer os mandatos do ente inteiro por execucao do LATERAL, agora sem indice nenhum por
-- vereador). Mesmo racional do down da mig 0069.
CREATE INDEX IF NOT EXISTS idx_mandato_vereador
  ON cadastros.mandato (ente_id, vereador_id);
--;;
CREATE INDEX IF NOT EXISTS idx_comissao_cargo_vereador
  ON cadastros.comissao_cargo (ente_id, vereador_id);
--;;
DROP INDEX IF EXISTS cadastros.idx_mandato_vereador_vigencia;
--;;
DROP INDEX IF EXISTS cadastros.idx_comissao_cargo_vereador_vigencia;
