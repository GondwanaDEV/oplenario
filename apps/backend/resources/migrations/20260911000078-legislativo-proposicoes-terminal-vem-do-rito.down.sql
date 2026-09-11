-- Reverte: recria a trava LITERAL de estado terminal em `legislativo.proposicoes` exatamente como a mig
-- 0013 a criou. ATENCAO ao rodar isto num banco que ja' tramitou: uma Casa cujo rito declare
-- 'arquivada'/'publicada' como NAO-terminal volta a ter o desarquivamento abortado pelo banco, e o erro
-- sai como check_violation crua (23514), que nenhum catch do legislativo traduz — vira 500 opaco na borda.
CREATE TRIGGER trg_proposicoes_imut_estado
  BEFORE UPDATE ON legislativo.proposicoes
  FOR EACH ROW EXECUTE FUNCTION shared.imut_trava_estado_terminal('publicada', 'arquivada');
