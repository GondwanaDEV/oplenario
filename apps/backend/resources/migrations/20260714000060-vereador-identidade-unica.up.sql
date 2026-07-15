-- Onda D Slice 5 Task 9: uma identidade nao pode estar ligada a DOIS vereadores na mesma Casa (seria a
-- mesma pessoa com dois assentos). COALESCE-unique nao serve aqui: identidade_id NULL e' o estado normal
-- de quem ainda nao tem acesso, e varios NULL devem coexistir -> indice PARCIAL (WHERE NOT NULL).
CREATE UNIQUE INDEX IF NOT EXISTS idx_vereador_identidade_unica
  ON cadastros.vereador (ente_id, identidade_id)
  WHERE identidade_id IS NOT NULL;
