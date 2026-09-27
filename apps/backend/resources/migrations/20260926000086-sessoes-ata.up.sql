-- Faixa A / A.6a da Track IA — a ATA como artefato legal do core (§22.3.4: "ata revisada e publicada vive no core";
-- §22.6: `ata_publicada` com o discriminador `origem_redacao`). Uma linha por VERSAO publicada; versao > 1 e' uma
-- RETIFICACAO e exige o motivo. Append-only (Inv. 10): a ata publicada nunca muda nem some — corrigir = nova versao.
--
-- `origem_redacao`: 'redigida_externamente' (a secretaria redigiu ou colou a ata — o caminho manual, que coexiste com a
-- IA e alimenta o golden de avaliacao) | 'gerada_automaticamente' (partiu de um rascunho da IA revisado por uma pessoa;
-- `rascunho_id` obrigatorio, com modelo e versao do prompt — proveniencia, §22.3.5). A publicacao e' CONGELADA por
-- hash SHA-256 do texto, como a folha da sessao; assinatura ICP-Brasil real segue `[GAP]` (§22.5 eixo F).
CREATE TABLE IF NOT EXISTS sessoes.ata (
  ente_id            uuid NOT NULL,
  id                 uuid NOT NULL DEFAULT gen_random_uuid(),
  sessao_id          uuid NOT NULL,
  versao             integer NOT NULL CHECK (versao > 0),
  origem_redacao     text NOT NULL CHECK (origem_redacao IN ('redigida_externamente', 'gerada_automaticamente')),
  texto              text NOT NULL CHECK (length(btrim(texto)) > 0 AND length(texto) <= 200000),
  conteudo_sha256    text NOT NULL,
  motivo_retificacao text,
  rascunho_id        uuid,
  modelo_llm_id      text,
  prompt_versao      text,
  proporcao_alterada numeric(5, 4),
  publicada_por      uuid NOT NULL,
  publicada_em       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  UNIQUE (ente_id, sessao_id, versao),
  FOREIGN KEY (ente_id, sessao_id) REFERENCES sessoes.sessao (ente_id, id),
  CONSTRAINT ata_retificacao_tem_motivo CHECK (versao = 1 OR length(btrim(coalesce(motivo_retificacao, ''))) > 0),
  CONSTRAINT ata_gerada_tem_rascunho CHECK (origem_redacao <> 'gerada_automaticamente' OR rascunho_id IS NOT NULL)
);
--;;
ALTER TABLE sessoes.ata ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE sessoes.ata FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON sessoes.ata;
--;;
CREATE POLICY tenant_isolation ON sessoes.ata
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT ON sessoes.ata TO oplenario_app;
--;;
CREATE TRIGGER trg_ata_append_only
  BEFORE UPDATE OR DELETE ON sessoes.ata
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
