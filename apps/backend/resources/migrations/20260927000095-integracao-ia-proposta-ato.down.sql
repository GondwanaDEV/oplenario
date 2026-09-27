ALTER TABLE integracao_ia.chamada_agente DROP CONSTRAINT IF EXISTS chamada_agente_desfecho_check;
--;;
ALTER TABLE integracao_ia.chamada_agente ADD CONSTRAINT chamada_agente_desfecho_check
  CHECK (desfecho IN ('ok', 'nao_encontrado', 'negado', 'invalido', 'erro'));
--;;
DROP TABLE IF EXISTS integracao_ia.leitura_de_terceiro;
--;;
DROP TABLE IF EXISTS integracao_ia.proposta_ato;
