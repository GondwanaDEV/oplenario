-- Desfaz a coluna do rito da materia no portal (projecao: o dado e' re-derivavel dos proximos eventos). So' remove a
-- coluna; nao devolve nem publica nada.
ALTER TABLE transparencia.materia DROP COLUMN IF EXISTS rito;
