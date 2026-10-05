-- Desfaz o DDL da tentativa. As linhas 'iniciado' ja' gravadas NAO saem (a trilha e' append-only): o CHECK antigo volta
-- como NOT VALID — vale para o que for gravado dali em diante e nao reprova o que ja' esta' na corrente.
DROP INDEX IF EXISTS auditoria.idx_registro_tentativa;
--;;
ALTER TABLE auditoria.registro DROP CONSTRAINT IF EXISTS registro_decisao_check;
--;;
ALTER TABLE auditoria.registro ADD CONSTRAINT registro_decisao_check
  CHECK (decisao IN ('permitido', 'negado', 'falhou')) NOT VALID;
