-- Faixa B / B.8 da Track IA (docs/25 Eixos 3.1 b e 3.3, ADR-0013) — a CONCESSAO de um agente INSTITUCIONAL da Casa: o
-- agente sem pessoa por tras (ex.: a conferencia de toda proposicao protocolada contra a LOM/RI), que o `admin_ente`
-- liga e desliga. E' o "vinculo" do agente: sem concessao ATIVA a credencial institucional nao e' emitida e, se ja'
-- emitida, o ator dela resolve sem papel algum na chamada seguinte (Eixo 3.2, revogar derruba na hora).
--
-- Classes so' `leitura` e `rascunho` (3.1 b): o agente institucional nunca recebe `ato` — CHECK aqui, CHECK na
-- credencial (mig 0092) e negacao no kernel do catalogo. Historico preservado: revogar fecha a linha, conceder de novo
-- abre outra; no maximo uma ativa por (Casa, agente).
CREATE TABLE IF NOT EXISTS identidade.concessao_agente (
  ente_id       uuid NOT NULL,
  id            uuid NOT NULL DEFAULT gen_random_uuid(),
  agente        text NOT NULL CHECK (agente ~ '^[a-z][a-z0-9-]{2,62}$'),
  classes       text[] NOT NULL CHECK (array_length(classes, 1) > 0
                                       AND classes <@ ARRAY['leitura', 'rascunho']::text[]),
  concedida_por uuid NOT NULL,
  concedida_em  timestamptz NOT NULL DEFAULT now(),
  revogada_por  uuid,
  revogada_em   timestamptz,
  PRIMARY KEY (ente_id, id),
  CONSTRAINT concessao_revogacao_completa CHECK ((revogada_em IS NULL) = (revogada_por IS NULL))
);
--;;
CREATE UNIQUE INDEX IF NOT EXISTS uq_concessao_agente_ativa ON identidade.concessao_agente (ente_id, agente)
  WHERE revogada_em IS NULL;
--;;
ALTER TABLE identidade.concessao_agente ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE identidade.concessao_agente FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON identidade.concessao_agente;
--;;
CREATE POLICY tenant_isolation ON identidade.concessao_agente
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE (revogada_por, revogada_em) ON identidade.concessao_agente TO oplenario_app;
--;;
-- a credencial delegada ganha o publico `institucional` (o conjunto de ferramentas do agente da Casa); so' ele pode
-- vir sem pessoa, e sem pessoa so' ele pode vir. NOT VALID: vale para toda credencial nova; as antigas vivem 15 min.
ALTER TABLE identidade.credencial_agente DROP CONSTRAINT IF EXISTS credencial_agente_publico_check;
--;;
ALTER TABLE identidade.credencial_agente ADD CONSTRAINT credencial_agente_publico_check
  CHECK (publico IN ('secretaria', 'vereador', 'cidadao', 'institucional'));
--;;
ALTER TABLE identidade.credencial_agente ADD CONSTRAINT credencial_institucional_sem_pessoa
  CHECK ((publico = 'institucional') = (identidade_id IS NULL)) NOT VALID;
