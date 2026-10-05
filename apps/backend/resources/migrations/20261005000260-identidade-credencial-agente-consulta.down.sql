ALTER TABLE identidade.credencial_agente DROP CONSTRAINT IF EXISTS credencial_consulta_so_leitura;
--;;
DELETE FROM identidade.credencial_agente WHERE publico = 'consulta';
--;;
ALTER TABLE identidade.credencial_agente DROP CONSTRAINT IF EXISTS credencial_agente_publico_check;
--;;
ALTER TABLE identidade.credencial_agente ADD CONSTRAINT credencial_agente_publico_check
  CHECK (publico IN ('secretaria', 'vereador', 'cidadao', 'institucional'));
