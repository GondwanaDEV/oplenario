-- ADR-0024: a entrada pelo CPF. A pessoa digita o CPF numa tela do O Plenario; o backend precisa saber em quais Casas
-- ela tem acesso institucional para mandar o navegador ao realm certo do Keycloak.
--
-- O vinculo e' TENANT (FORCE RLS por `app.ente_id`): o pool de runtime, sem a Casa no GUC, nao ve linha nenhuma, e
-- essa propriedade NAO muda aqui. Em vez de abrir a tabela, existe UMA pergunta que atravessa as Casas, numa funcao
-- estreita: "em quais Casas esta identidade tem vinculo institucional ATIVO?" — devolve so' os `ente_id`.
--
-- Como a funcao enxerga as outras Casas: ela e' SECURITY DEFINER do DONO das tabelas (quem roda as migrations), e so'
-- o dono ganha uma politica de SELECT irrestrita no vinculo. O dono ja' manda nas tabelas (pode tirar o FORCE quando
-- quiser): a politica nao da' a ele nada que ele nao tivesse, e vale tanto com dono superuser (compose, deploy atual)
-- quanto com dono restrito (Postgres gerenciado — `encerramento_test` migra assim). O EXECUTE e' so' do
-- `oplenario_id_resolver` (do qual o pool herda); o dominio (`oplenario_app`) nao chama.
--
-- Institucional = `servidor`, `vereador`, `admin_ente`. O vinculo de `cidadao` entra pelo gov.br, nunca por senha.
DROP POLICY IF EXISTS localizar_casas ON identidade.vinculo;
--;;
CREATE POLICY localizar_casas ON identidade.vinculo FOR SELECT TO CURRENT_USER USING (true);
--;;
CREATE OR REPLACE FUNCTION identidade.casas_com_acesso_institucional(p_identidade uuid)
RETURNS TABLE (ente_id uuid)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
  SELECT DISTINCT v.ente_id
    FROM identidade.vinculo v
   WHERE v.identidade_id = p_identidade
     AND v.estado = 'ativo'
     AND v.tipo IN ('servidor', 'vereador', 'admin_ente')
   ORDER BY v.ente_id
$$;
--;;
REVOKE ALL ON FUNCTION identidade.casas_com_acesso_institucional(uuid) FROM PUBLIC;
--;;
GRANT EXECUTE ON FUNCTION identidade.casas_com_acesso_institucional(uuid) TO oplenario_id_resolver;
