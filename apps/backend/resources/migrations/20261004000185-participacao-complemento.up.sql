-- O COMPLEMENTO DA RESPOSTA (ADR-0022, "Complemento da resposta"): depois que a janela de 10 minutos dos anexos fechou, a
-- secretaria ainda pode ACRESCENTAR algo a um protocolo que a Casa ja' respondeu (respondido, indeferido ou, no e-SIC, com
-- o recurso decidido). E' um ato proprio e IMUTAVEL: a resposta continua intacta, o complemento e' outra linha, com autor e
-- instante. Nao mexe em estado nem em prazo (o prazo ja' foi cumprido pela resposta): e' so' texto a mais no historico.
--
-- Mesmo molde de `participacao.anexo`: o protocolo e' POLIMORFICO (objeto_tipo + objeto_id, sem FK, porque o alvo mora em
-- tres tabelas; quem confere que ele existe nesta Casa e que ja' foi respondido e' a aplicacao, e a RLS isola a Casa).
-- Sem limite numerico de complementos: cada um e' um ato. O texto tem o mesmo teto do texto da resposta (50000).
--
-- `complementado_por` (a identidade da secretaria que escreveu) e' de AUDITORIA da Casa: o balcao mostra o NOME de quem
-- agiu, mas o cidadao nunca recebe a identidade (o contrato de saida do portal nem tem a chave).
CREATE TABLE IF NOT EXISTS participacao.complemento (
  ente_id           uuid        NOT NULL,
  id                uuid        NOT NULL DEFAULT gen_random_uuid(),
  -- a ORDEM DE CHEGADA: o `complementado_em` e' o relogio da aplicacao e dois complementos no mesmo instante empatam; a
  -- identidade de insercao nao
  seq               bigint      GENERATED ALWAYS AS IDENTITY,
  objeto_tipo       text        NOT NULL CHECK (objeto_tipo IN ('pedido_esic', 'manifestacao_ouvidoria', 'solicitacao_titular')),
  objeto_id         uuid        NOT NULL,
  corpo             text        NOT NULL CHECK (btrim(corpo) <> '' AND char_length(corpo) <= 50000),
  complementado_em  timestamptz NOT NULL DEFAULT now(),
  complementado_por uuid        NOT NULL,                  -- GUARD ref (identidade), sem FK cross-schema
  PRIMARY KEY (ente_id, id)
);
--;;
-- "os complementos deste protocolo" (o detalhe do balcao e a lista do cidadao), na ordem em que chegaram
CREATE INDEX IF NOT EXISTS idx_participacao_complemento_objeto
  ON participacao.complemento (ente_id, objeto_tipo, objeto_id, seq);
--;;
-- append-only: UPDATE/DELETE recusados pelo banco (alem de nao haver GRANT), mesmo para quem tiver o GRANT
CREATE TRIGGER trg_participacao_complemento_append_only BEFORE UPDATE OR DELETE ON participacao.complemento
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
--;;
ALTER TABLE participacao.complemento ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE participacao.complemento FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON participacao.complemento;
--;;
CREATE POLICY tenant_isolation ON participacao.complemento
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT ON participacao.complemento TO oplenario_app;
