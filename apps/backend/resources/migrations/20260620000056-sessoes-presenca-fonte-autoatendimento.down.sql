DROP INDEX IF EXISTS sessoes.idx_presenca_evento_corrente;
--;;
ALTER TABLE sessoes.presenca_evento DROP CONSTRAINT IF EXISTS presenca_evento_fonte_check;
--;;
-- reverte a CHECK: qualquer linha 'autoatendimento' ja gravada bloquearia este DOWN (esperado — down nao e
-- p/ rodar com dado incompativel presente; mesma disciplina das demais migrations deste projeto).
ALTER TABLE sessoes.presenca_evento ADD CONSTRAINT presenca_evento_fonte_check
  CHECK (fonte IN ('painel_eletronico', 'manual_secretaria', 'inferida_por_voto', 'inferida_por_tribuna'));
--;;
ALTER TABLE sessoes.presenca_evento DROP COLUMN fonte_precedencia;
--;;
ALTER TABLE sessoes.presenca_evento ADD COLUMN fonte_precedencia integer NOT NULL GENERATED ALWAYS AS (
  CASE fonte WHEN 'manual_secretaria' THEN 3 WHEN 'painel_eletronico' THEN 2 ELSE 1 END) STORED;
--;;
CREATE INDEX IF NOT EXISTS idx_presenca_evento_corrente
  ON sessoes.presenca_evento (ente_id, sessao_id, vereador_id, ocorrido_em DESC, fonte_precedencia DESC, id DESC)
  INCLUDE (tipo, modalidade);
