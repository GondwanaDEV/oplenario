-- Simetrico do up: tira a regra da emenda a LOM e as duas colunas. Nenhuma votacao e' tocada; a PELOM volta a abrir
-- com o quorum que a Mesa escolher.
DELETE FROM legislativo.regra_votacao_materia WHERE chave = 'emenda_lom';
--;;
ALTER TABLE legislativo.regra_votacao_materia
  DROP COLUMN IF EXISTS intersticio_dias,
  DROP COLUMN IF EXISTS turnos;
