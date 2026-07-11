-- Onda C3: nova fonte de presenca 'autoatendimento' (o vereador confirma a propria presenca pelo celular,
-- POST /sessoes/:id/presenca/confirmar). Precedencia (desempate de MESMO instante, logic/precedencia-fonte):
-- manual_secretaria > painel_eletronico > autoatendimento > inferida_* — a Mesa (manual ou painel fisico)
-- sempre pode sobrepor um autoatendimento do proprio vereador; autoatendimento vale mais que uma INFERENCIA
-- (voto/tribuna sem check-in). Precisa DROP+ADD da coluna gerada (Postgres nao altera a expressao de uma
-- GENERATED ALWAYS AS em ALTER COLUMN) — o indice que a usa precisa ser derrubado antes e recriado depois.

DROP INDEX IF EXISTS sessoes.idx_presenca_evento_corrente;
--;;
ALTER TABLE sessoes.presenca_evento DROP CONSTRAINT IF EXISTS presenca_evento_fonte_check;
--;;
ALTER TABLE sessoes.presenca_evento ADD CONSTRAINT presenca_evento_fonte_check
  CHECK (fonte IN ('painel_eletronico', 'manual_secretaria', 'autoatendimento', 'inferida_por_voto', 'inferida_por_tribuna'));
--;;
ALTER TABLE sessoes.presenca_evento DROP COLUMN fonte_precedencia;
--;;
ALTER TABLE sessoes.presenca_evento ADD COLUMN fonte_precedencia integer NOT NULL GENERATED ALWAYS AS (
  CASE fonte
    WHEN 'manual_secretaria' THEN 4
    WHEN 'painel_eletronico' THEN 3
    WHEN 'autoatendimento'   THEN 2
    ELSE 1
  END) STORED;
--;;
CREATE INDEX IF NOT EXISTS idx_presenca_evento_corrente
  ON sessoes.presenca_evento (ente_id, sessao_id, vereador_id, ocorrido_em DESC, fonte_precedencia DESC, id DESC)
  INCLUDE (tipo, modalidade);
