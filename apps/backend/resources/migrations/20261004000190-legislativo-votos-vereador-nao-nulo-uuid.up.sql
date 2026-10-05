-- legislativo.votos.vereador_id e' GUARD REF (uuid NOT NULL, sem FK): o vereador vive em `cadastros`, e FK/JOIN
-- cross-schema e' proibido (ADR-0001 §6; `votos.vereador_id`, mig 20260620000021 e `pareceres.comissao_id`, mig
-- 20260620000019, seguem o mesmo precedente). A pertenca do vereador a Casa e' garantida NA APLICACAO, antes de
-- qualquer escrita: `controllers/registrar-voto` (rota da Mesa) exige mandato vigente no roster da Casa
-- (`rotas/vereador-no-roster`) e `controllers/meu-voto` resolve o vereador do proprio ator + policy de mandato e
-- presenca. O que o banco PODE garantir sozinho, dentro do schema, e' o que este CHECK faz: o UUID nulo
-- (00000000-...) nunca e' um vereador — e' o valor que um default/placeholder esquecido produziria.
--
-- NOT VALID de proposito: vale para toda linha NOVA sem varrer nem reprovar dado legado (voto e' append-only e
-- nunca se apaga). Em seguida tenta VALIDAR; se existir linha que o viole (so' e' possivel por escrita direta, a
-- aplicacao nao produz), a validacao falha, o CHECK fica NOT VALID e a migration segue com um NOTICE.
ALTER TABLE legislativo.votos
  ADD CONSTRAINT votos_vereador_id_nao_nulo_chk
  CHECK (vereador_id <> '00000000-0000-0000-0000-000000000000'::uuid) NOT VALID;
--;;
DO $$
BEGIN
  ALTER TABLE legislativo.votos VALIDATE CONSTRAINT votos_vereador_id_nao_nulo_chk;
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'legislativo.votos: ha voto legado com vereador_id nulo; CHECK votos_vereador_id_nao_nulo_chk fica NOT VALID (vale so para linhas novas)';
END $$;
