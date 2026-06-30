ALTER TABLE compliance.compliance_avaliacao DROP CONSTRAINT IF EXISTS chk_avaliacao_severidade;
--;;
ALTER TABLE compliance.compliance_avaliacao DROP CONSTRAINT IF EXISTS chk_avaliacao_origem;
--;;
ALTER TABLE compliance.compliance_avaliacao DROP CONSTRAINT IF EXISTS chk_avaliacao_veredito;
--;;
ALTER TABLE compliance.prazo_dominio_ativo DROP CONSTRAINT IF EXISTS chk_obrigacao_estado;
--;;
ALTER TABLE compliance.remessa_gerada DROP CONSTRAINT IF EXISTS chk_remessa_estado;
