-- Anexos do ATENDIMENTO ao cidadao (e-SIC, ouvidoria, LGPD): a resposta a um pedido costuma SER um documento (planilha,
-- copia de contrato). A secretaria anexa arquivos a resposta de um protocolo; o requerente os baixa em /meus-protocolos.
-- O requerente tambem anexa ao proprio pedido (origem `requerente`); a secretaria RETIRA um anexo (das duas origens) por
-- incidente de conteudo — `participacao.anexo_retirada`, abaixo.
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
-- tem a chave). `origem` = quem anexou: `casa` (a secretaria, na resposta) ou `requerente` (o cidadao, no pedido).
CREATE TABLE IF NOT EXISTS participacao.anexo (
  ente_id       uuid        NOT NULL,
  id            uuid        NOT NULL DEFAULT gen_random_uuid(),
  -- a ORDEM DE CHEGADA (as listas saem "na ordem em que chegaram"): o `enviado_em` e' o relogio da aplicacao e dois envios
  -- no mesmo instante empatam; a identidade de insercao nao
  seq           bigint      GENERATED ALWAYS AS IDENTITY,
  objeto_tipo   text        NOT NULL CHECK (objeto_tipo IN ('pedido_esic', 'manifestacao_ouvidoria', 'solicitacao_titular')),
  objeto_id     uuid        NOT NULL,
  origem        text        NOT NULL CHECK (origem IN ('casa', 'requerente')),
  -- o nome e' so' texto de exibicao: sem aspas nem caractere de controle (a borda ja' limpa; o banco barra o resto)
  nome          text        NOT NULL CHECK (btrim(nome) <> '' AND char_length(nome) <= 200 AND nome !~ '[\x01-\x1f\x7f-\x9f"]'),
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
-- a cota de disco do cidadao: "quantos bytes esta identidade anexou nas ultimas 24 h nesta Casa" (so' a origem requerente)
CREATE INDEX IF NOT EXISTS idx_participacao_anexo_cota_do_requerente
  ON participacao.anexo (ente_id, enviado_por, enviado_em) WHERE origem = 'requerente';
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
--;;
-- A RETIRADA de um anexo (incidente de conteudo: um arquivo que nao devia estar la). O anexo e' append-only e fica como
-- prova (nome, tamanho, sha256, quem enviou); a retirada e' OUTRA linha, tambem append-only, que diz quando, por quem e
-- POR QUE. O efeito (o blob sai do object storage, o download vira 404 para todos, a lista mostra "retirado", a vaga
-- do limite de 5 volta) e' da aplicacao, lendo esta tabela. O `motivo` e' obrigatorio e so' o balcao o le (o requerente
-- ve so' que foi retirado e quando). Uma retirada por anexo: retirar de novo e' o mesmo efeito, sem segunda linha.
CREATE TABLE IF NOT EXISTS participacao.anexo_retirada (
  ente_id       uuid        NOT NULL,
  anexo_id      uuid        NOT NULL,
  retirado_em   timestamptz NOT NULL DEFAULT now(),
  retirado_por  uuid        NOT NULL,                  -- GUARD ref (identidade), sem FK cross-schema
  motivo        text        NOT NULL CHECK (btrim(motivo) <> '' AND char_length(motivo) <= 1000),
  PRIMARY KEY (ente_id, anexo_id),
  FOREIGN KEY (ente_id, anexo_id) REFERENCES participacao.anexo (ente_id, id)
);
--;;
CREATE TRIGGER trg_participacao_anexo_retirada_append_only BEFORE UPDATE OR DELETE ON participacao.anexo_retirada
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
--;;
ALTER TABLE participacao.anexo_retirada ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE participacao.anexo_retirada FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON participacao.anexo_retirada;
--;;
CREATE POLICY tenant_isolation ON participacao.anexo_retirada
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT ON participacao.anexo_retirada TO oplenario_app;
