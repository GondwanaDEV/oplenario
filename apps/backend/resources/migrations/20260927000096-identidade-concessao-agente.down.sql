ALTER TABLE identidade.credencial_agente DROP CONSTRAINT IF EXISTS credencial_institucional_sem_pessoa;
--;;
DELETE FROM identidade.credencial_agente WHERE publico = 'institucional';
--;;
ALTER TABLE identidade.credencial_agente DROP CONSTRAINT IF EXISTS credencial_agente_publico_check;
--;;
ALTER TABLE identidade.credencial_agente ADD CONSTRAINT credencial_agente_publico_check
  CHECK (publico IN ('secretaria', 'vereador', 'cidadao'));
--;;
DROP TABLE IF EXISTS identidade.concessao_agente;
