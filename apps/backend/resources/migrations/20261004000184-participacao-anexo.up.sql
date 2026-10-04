-- Anexos do ATENDIMENTO ao cidadao (e-SIC, ouvidoria, LGPD): a resposta a um pedido costuma SER um documento (planilha,
-- copia de contrato). A secretaria anexa arquivos a resposta de um protocolo; o requerente os baixa em /meus-protocolos.
-- (A fatia seguinte deixa o requerente anexar ao proprio pedido: a tabela ja' prevê a origem.)
--
-- Mesmo molde de `comunicacao.anexo`: o arquivo vai ao object storage sob `atendimento/<ente>/<protocolo>/<id>` (a
-- convencao `<pasta>/<ente>/` que a exportacao e o apagamento da Casa descobrem sozinhos, ADR-0018); aqui fica o nome,
-- o tipo, o tamanho, o sha256 e quem enviou. A aplicacao confere a janela, o limite e os tipos aceitos e serializa os
-- envios do mesmo protocolo com uma trava consultiva; o tamanho e a convencao de chave tambem sao CHECK.
--
-- O protocolo e' POLIMORFICO (objeto_tipo + objeto_id, como `prazo_ativo` e `prorrogacao`): nao ha FK, porque o alvo mora
-- em tres tabelas; quem confere que o protocolo existe nesta Casa e' a aplicacao (a RLS isola, e a chave de convencao
-- amarra o anexo a Casa e ao protocolo).
--
-- `enviado_por` (a identidade que enviou) e' de AUDITORIA da Casa: nunca sai para a outra parte (o contrato de saida nem
-- tem a chave). `origem` = quem anexou: `casa` (a secretaria, na resposta) ou `requerente` (fatia seguinte).
CREATE TABLE IF NOT EXISTS participacao.anexo (
  ente_id       uuid        NOT NULL,
  id            uuid        NOT NULL DEFAULT gen_random_uuid(),
  objeto_tipo   text        NOT NULL CHECK (objeto_tipo IN ('pedido_esic', 'manifestacao_ouvidoria', 'solicitacao_titular')),
  objeto_id     uuid        NOT NULL,
  origem        text        NOT NULL CHECK (origem IN ('casa', 'requerente')),
  nome          text        NOT NULL CHECK (btrim(nome) <> '' AND char_length(nome) <= 200),
  tipo_midia    text        NOT NULL CHECK (char_length(tipo_midia) <= 200),
  bytes         bigint      NOT NULL CHECK (bytes > 0 AND bytes <= 10485760),
  sha256        text        NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  chave_objeto  text        NOT NULL,
  enviado_em    timestamptz NOT NULL DEFAULT now(),
  enviado_por   uuid        NOT NULL,                  -- GUARD ref (identidade), sem FK cross-schema
  PRIMARY KEY (ente_id, id),
  -- a chave segue a convencao `<pasta>/<ente>/` — e' o que o apagamento da Casa (ADR-0018) encontra sozinho
  CONSTRAINT anexo_chave_da_convencao
    CHECK (chave_objeto = 'atendimento/' || ente_id || '/' || objeto_id || '/' || id)
);
--;;
-- "os anexos deste protocolo" (o detalhe do balcao e a lista do cidadao), na ordem em que chegaram
CREATE INDEX IF NOT EXISTS idx_participacao_anexo_objeto
  ON participacao.anexo (ente_id, objeto_tipo, objeto_id, enviado_em);
--;;
-- append-only: UPDATE/DELETE recusados pelo banco (alem de nao haver GRANT), mesmo para quem tiver o GRANT
CREATE TRIGGER trg_participacao_anexo_append_only BEFORE UPDATE OR DELETE ON participacao.anexo
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
--;;
ALTER TABLE participacao.anexo ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE participacao.anexo FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON participacao.anexo;
--;;
CREATE POLICY tenant_isolation ON participacao.anexo
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT ON participacao.anexo TO oplenario_app;
