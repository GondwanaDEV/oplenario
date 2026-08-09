ALTER TABLE sessoes.sessao DROP CONSTRAINT IF EXISTS sessao_encerrada_em_obrigatoria;
--;;
ALTER TABLE sessoes.sessao ADD CONSTRAINT sessao_encerrada_em_obrigatoria
  CHECK (estado NOT IN ('encerrada', 'nao_realizada') OR encerrada_em IS NOT NULL);
