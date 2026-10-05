-- Simetrico do up: derruba o indice por vereador. Nenhum dado de voto e' tocado (so' o indice some; a leitura
-- volta a varrer os votos da Casa, mais lenta mas com o mesmo resultado).
DROP INDEX IF EXISTS legislativo.idx_votos_vereador_registrado;
