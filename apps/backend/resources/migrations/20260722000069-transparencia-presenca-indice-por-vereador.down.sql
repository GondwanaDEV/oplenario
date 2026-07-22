-- Simetrico do up, em ordem inversa: derruba o covering do numerador e recria o indice de ente que o up
-- limpou. Recriar `idx_presenca_parlamentar_ente` e' o que mantem o rollback FIEL ao estado da mig 0065 —
-- ele e' redundante (prefixo estrito da PK), mas um down que nao o recria deixaria o banco num estado que
-- nenhuma sequencia de migrations produz.
DROP INDEX IF EXISTS transparencia.idx_presenca_parlamentar_vereador_sessao;
--;;
CREATE INDEX IF NOT EXISTS idx_presenca_parlamentar_ente
  ON transparencia.presenca_parlamentar (ente_id);
