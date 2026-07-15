-- Review Task 9 IMPORTANT-1: `idx_vereador_identidade` (migration 20260620000014, ja' aplicada) cobre
-- as MESMAS colunas (ente_id, identidade_id) com o MESMO predicado WHERE identidade_id IS NOT NULL que
-- o `idx_vereador_identidade_unica` (migration 20260714000060) -- so' nao-unico. O indice UNIQUE serve
-- toda leitura que o antigo servia (inclusive `vereador/por-identidade`) e ainda garante a restricao ->
-- o antigo fica redundante: todo INSERT/UPDATE em cadastros.vereador mantinha DOIS btrees identicos a
-- toa, custando escrita sem ganho nenhum de leitura.
--
-- NUNCA editar a migration 20260714000060 (ja' aplicada -- Migratus registra por id e nao re-roda) nem
-- a 20260620000014 (idem) -- arquivo NOVO, como manda o protocolo deste projeto.
DROP INDEX IF EXISTS cadastros.idx_vereador_identidade;
