-- Faixa A / A.7 da Track IA — a LEITURA DA ATA ANTERIOR como ato da sessao (pedido de Baturite: "IA, presencial ou
-- dispensada"). Uma linha por sessao: qual ata foi lida (a VERSAO publicada, por FK — so' se le ata revisada e
-- publicada, nunca rascunho) e como. Append-only: o ato de leitura nao se desfaz.
--
-- `modo`: 'voz_sintetizada' (o sistema leu em voz; a voz desta fatia e' a do navegador da Mesa, provisoria ate' a
-- decisao do fornecedor), 'presencial' (o secretario leu) ou 'dispensada' (a Casa dispensou a leitura — ata
-- distribuida antes). O hash da versao lida fica gravado: prova do texto que o plenario ouviu.
CREATE TABLE IF NOT EXISTS sessoes.leitura_ata (
  ente_id         uuid NOT NULL,
  id              uuid NOT NULL DEFAULT gen_random_uuid(),
  sessao_id       uuid NOT NULL,
  ata_sessao_id   uuid NOT NULL,
  ata_versao      integer NOT NULL,
  ata_conteudo_sha256 text NOT NULL,
  modo            text NOT NULL CHECK (modo IN ('voz_sintetizada', 'presencial', 'dispensada')),
  registrada_por  uuid NOT NULL,
  registrada_em   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  UNIQUE (ente_id, sessao_id),
  FOREIGN KEY (ente_id, sessao_id) REFERENCES sessoes.sessao (ente_id, id),
  FOREIGN KEY (ente_id, ata_sessao_id, ata_versao) REFERENCES sessoes.ata (ente_id, sessao_id, versao),
  CONSTRAINT leitura_de_outra_sessao CHECK (ata_sessao_id <> sessao_id)
);
--;;
ALTER TABLE sessoes.leitura_ata ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE sessoes.leitura_ata FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON sessoes.leitura_ata;
--;;
CREATE POLICY tenant_isolation ON sessoes.leitura_ata
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT ON sessoes.leitura_ata TO oplenario_app;
--;;
CREATE TRIGGER trg_leitura_ata_append_only
  BEFORE UPDATE OR DELETE ON sessoes.leitura_ata
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
