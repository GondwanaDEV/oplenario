-- ADR-0015: a sessao aberta pelo gov.br e' SO' de cidadao. NULL = sessao institucional (o vinculo ativo da
-- identidade, como antes); 'cidadao' = o ator e' o vinculo de cidadao com zero papeis, mesmo que a mesma
-- identidade seja vereadora na Casa — poder institucional exige o login institucional (passkey).
ALTER TABLE identidade.sessao ADD COLUMN IF NOT EXISTS vinculo_tipo text
  CHECK (vinculo_tipo IS NULL OR vinculo_tipo = 'cidadao');
