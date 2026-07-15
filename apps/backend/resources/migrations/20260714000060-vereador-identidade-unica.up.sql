-- Onda D Slice 5 Task 9: uma identidade nao pode estar ligada a DOIS vereadores na mesma Casa (seria a
-- mesma pessoa com dois assentos). COALESCE-unique nao serve aqui: identidade_id NULL e' o estado normal
-- de quem ainda nao tem acesso, e varios NULL devem coexistir -> indice PARCIAL (WHERE NOT NULL).
CREATE UNIQUE INDEX IF NOT EXISTS idx_vereador_identidade_unica
  ON cadastros.vereador (ente_id, identidade_id)
  WHERE identidade_id IS NOT NULL;
--;;
-- Review Task 9 IMPORTANT-1: `idx_vereador_identidade` (migration 20260620000014, ja' aplicada) cobria
-- as MESMAS colunas com o MESMO predicado, so' nao-unico. O indice UNIQUE acima serve toda leitura que o
-- antigo servia (inclusive `vereador/por-identidade`) e ainda garante a restricao -> o antigo fica
-- redundante: todo INSERT/UPDATE em cadastros.vereador passaria a manter DOIS btrees identicos a toa.
DROP INDEX IF EXISTS cadastros.idx_vereador_identidade;
