-- Portal do cidadao: a votacao de TURNO gravada antes da regra da emenda a Lei Organica (mig 20261005000250). A linha do
-- tempo publica ("Por onde a materia passou") guardou a votacao da PELOM como "Aprovada em plenario" — o rotulo de
-- materia de turno unico. Desde o PR #198 a projecao ja' grava "Aprovada em 1º turno" / "Aprovada em 2º turno"
-- (`transparencia.logic.desfecho/rotulo`, chave `ato:<ato>:turno_N`), mas as linhas antigas ficaram como estavam. Aqui
-- elas passam a dizer o turno, com a MESMA conta da projecao: turno = 1 + as aprovacoes de turno anteriores da materia,
-- ate' o numero de `turnos` da regra; a rejeicao e' do turno que se votava. A redacao final (`ato:*:redacao_final`) nao
-- e' turno e nao muda.
--
-- Qual materia vota em turnos: a especie `proposta_emenda_lom`, cuja regra e' `emenda_lom` (a mesma escolha de
-- `legislativo.logic.regra-votacao/chave-da-materia`). So' se a regra existe com mais de um turno.
--
-- As tres perguntas de migration:
--   1. Muda dado existente? Sim, e so' isto: rotulo e chave das linhas de votacao (`ato:aprovada`/`ato:rejeitada`) de
--      emenda a LOM no read-model do portal. A votacao em `legislativo` nao e' tocada (ato consumado, append-only), nem
--      o `desfecho` da materia (o selo so' muda a partir do autografo). A projecao e' derivada: o evento original,
--      redespachado, cai na chave nova e nao duplica (ON CONFLICT da chave natural).
--   2. Pode falhar conforme o dado ou o relogio? Nao: o UPDATE pula a linha cuja chave nova ja' existe (a PK nao
--      colide) e nao depende de data. Sem linhas a mudar, nao faz nada.
--   3. Enxerga todas as Casas? Sim: as duas tabelas do portal tem FORCE RLS e a migration nao seta `app.ente_id`, entao
--      o FORCE e' desligado e religado na mesma transacao (mesma disciplina das migs 20261005000202 e 0210).
ALTER TABLE transparencia.materia_movimentacao NO FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE transparencia.materia NO FORCE ROW LEVEL SECURITY;
--;;
CREATE TEMP TABLE turno_das_votacoes_antigas ON COMMIT DROP AS
WITH regra AS (
  SELECT turnos FROM legislativo.regra_votacao_materia WHERE chave = 'emenda_lom' AND turnos > 1
), votacoes AS (
  -- as votacoes de turno da materia (as antigas e as ja' projetadas com o turno), em ordem
  SELECT mv.ente_id, mv.proposicao_id, mv.ocorrido_em, mv.etapa_chave,
         split_part(mv.etapa_chave, ':', 2) AS ato,
         count(*) FILTER (WHERE split_part(mv.etapa_chave, ':', 2) = 'aprovada')
           OVER (PARTITION BY mv.ente_id, mv.proposicao_id ORDER BY mv.ocorrido_em, mv.etapa_chave
                 ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS aprovadas_antes
    FROM transparencia.materia_movimentacao mv
    JOIN transparencia.materia m ON m.ente_id = mv.ente_id AND m.proposicao_id = mv.proposicao_id
   WHERE m.tipo = 'proposta_emenda_lom'
     AND mv.etapa_chave ~ '^ato:(aprovada|rejeitada)(:turno_[0-9]+)?$'
)
SELECT v.ente_id, v.proposicao_id, v.ocorrido_em, v.etapa_chave, v.ato,
       LEAST(r.turnos, COALESCE(v.aprovadas_antes, 0) + 1) AS turno
  FROM votacoes v CROSS JOIN regra r
 WHERE v.etapa_chave IN ('ato:aprovada', 'ato:rejeitada');
--;;
UPDATE transparencia.materia_movimentacao mv
   SET etapa_chave = t.etapa_chave || ':turno_' || t.turno,
       etapa = CASE t.ato WHEN 'aprovada' THEN 'Aprovada em ' ELSE 'Rejeitada em ' END || t.turno || 'º turno'
  FROM turno_das_votacoes_antigas t
 WHERE mv.ente_id = t.ente_id AND mv.proposicao_id = t.proposicao_id
   AND mv.ocorrido_em = t.ocorrido_em AND mv.etapa_chave = t.etapa_chave
   AND NOT EXISTS (SELECT 1 FROM transparencia.materia_movimentacao d
                    WHERE d.ente_id = t.ente_id AND d.proposicao_id = t.proposicao_id
                      AND d.ocorrido_em = t.ocorrido_em AND d.etapa_chave = t.etapa_chave || ':turno_' || t.turno);
--;;
ALTER TABLE transparencia.materia FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE transparencia.materia_movimentacao FORCE ROW LEVEL SECURITY;
