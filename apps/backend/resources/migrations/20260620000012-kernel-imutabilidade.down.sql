-- reverte F3.0: as funcoes de imutabilidade kernel. (Rollback ordenado: as migrations que ANEXAM o
-- trigger — F3.1+ — sao revertidas antes desta, removendo as dependencias.)
DROP FUNCTION IF EXISTS shared.imut_trava_estado_terminal();
--;;
DROP FUNCTION IF EXISTS shared.imut_append_only();
