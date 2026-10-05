-- Fatia 4 da Clara (ADR-0024, ADR-0010): o publico `consulta` — o juridico, o controle interno (auditor) e o
-- administrador da Casa perguntam a' Clara SO' PARA LER. A credencial delegada desse publico nunca recebe `ato` nem
-- `rascunho`: o agente.clj so' concede `leitura`, e o banco recusa outra coisa (mesma defesa em duas camadas do
-- `institucional_nunca_ato`, mig 0092). O CHECK do publico so' alarga (toda linha antiga ja' cumpre); o novo e' NOT
-- VALID, como o `credencial_institucional_sem_pessoa` (mig 0096): nao ha' linha `consulta` antes desta migration.
--
-- `integracao_ia.interacao_assistente.publico` (mig 0220) e' texto livre, sem CHECK: guarda `consulta` sem mudanca.
ALTER TABLE identidade.credencial_agente DROP CONSTRAINT IF EXISTS credencial_agente_publico_check;
--;;
ALTER TABLE identidade.credencial_agente ADD CONSTRAINT credencial_agente_publico_check
  CHECK (publico IN ('secretaria', 'vereador', 'cidadao', 'institucional', 'consulta'));
--;;
ALTER TABLE identidade.credencial_agente DROP CONSTRAINT IF EXISTS credencial_consulta_so_leitura;
--;;
ALTER TABLE identidade.credencial_agente ADD CONSTRAINT credencial_consulta_so_leitura
  CHECK (publico <> 'consulta' OR classes <@ ARRAY['leitura']::text[]) NOT VALID;
