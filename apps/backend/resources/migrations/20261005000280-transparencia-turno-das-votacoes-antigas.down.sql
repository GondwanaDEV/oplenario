-- Sem volta automatica: depois do up, uma linha `ato:aprovada:turno_1` pode ter vindo desta migration ou da projecao do
-- evento, e as duas sao indistinguiveis. O rotulo com o turno e' o verdadeiro; voltar a "Aprovada em plenario" seria
-- reescrever a linha do tempo com o rotulo que a materia de dois turnos nao tem.
SELECT 1;
