-- F3.9b: modulo legislativo, EXPEDIENTE — GERACAO DE DOCUMENTOS por modelo (§16.3, feature 3.22). O trabalho
-- mais frequente do servidor (oficio, certidao, requerimento adm., convite, mala-direta) — o MAIOR risco de
-- POC do catalogo. DUAS tabelas:
-- (1) documento_modelo: o TEMPLATE configuravel por ente (corpo com placeholders {{campo}}), MUTAVEL (config
--     do tenant, como template_tramitacao) — desativavel via `ativo`, nao se apaga (sem DELETE).
-- (2) documento: o documento GERADO (merge do dominio no template, logic/renderizar-documento). State machine
--     rascunho -> emitido; ao emitir o conteudo CONGELA (trava terminal nivel b). Pode vincular a entrada do
--     Protocolo Geral (F3.9a) via FK same-tenant. Assinatura ICP-Brasil = [GAP] (track cripto/NFR).
-- O MERGE usa FATOS resolvidos UPSTREAM (sem JOIN cross-schema, §22.10); dados_merge guarda o snapshot (audit).
-- NAO-particionadas (cardinalidade moderada; FK same-tenant inclui ente_id).

-- ====================== DOCUMENTO_MODELO (template, config mutavel do tenant) ======================
CREATE TABLE IF NOT EXISTS legislativo.documento_modelo (
  ente_id      uuid NOT NULL,
  id           uuid NOT NULL DEFAULT gen_random_uuid(),
  chave        text NOT NULL,                             -- chave estavel do modelo no ente (ex.: 'oficio_padrao')
  nome         text NOT NULL,
  tipo_documento text NOT NULL CHECK (tipo_documento IN
    ('oficio', 'certidao', 'requerimento_administrativo', 'convite', 'mala_direta', 'outro')),
  corpo_template text NOT NULL,                           -- corpo com placeholders {{campo}}
  ativo        boolean NOT NULL DEFAULT true,
  -- transversais (§22.4.3 disc.1); config muta -> updated_*/lock_version
  origem    text NOT NULL DEFAULT 'nativa',
  origem_ref text,
  origem_importado_em timestamptz,
  lote_id   uuid,
  efetivado_em timestamptz,
  lock_version integer NOT NULL DEFAULT 0,
  created_by uuid,
  updated_by uuid,
  criado_em    timestamptz NOT NULL DEFAULT now(),
  atualizado_em timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  UNIQUE (ente_id, chave),
  CONSTRAINT modelo_staging_valido CHECK (lote_id IS NOT NULL OR efetivado_em IS NOT NULL)
);
--;;
CREATE INDEX IF NOT EXISTS idx_modelo_ativo ON legislativo.documento_modelo (ente_id, tipo_documento) WHERE ativo;
--;;
CREATE INDEX IF NOT EXISTS idx_modelo_staging ON legislativo.documento_modelo (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE legislativo.documento_modelo ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.documento_modelo FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON legislativo.documento_modelo;
--;;
CREATE POLICY tenant_isolation ON legislativo.documento_modelo
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- modelo nao se apaga (desativa via `ativo`): sem DELETE.
GRANT SELECT, INSERT, UPDATE ON legislativo.documento_modelo TO oplenario_app;
--;;
-- ====================== DOCUMENTO (gerado: merge do dominio, state machine) ======================
CREATE TABLE IF NOT EXISTS legislativo.documento (
  ente_id      uuid NOT NULL,
  id           uuid NOT NULL DEFAULT gen_random_uuid(),
  modelo_id    uuid NOT NULL,                             -- o template de origem
  tipo_documento text NOT NULL CHECK (tipo_documento IN
    ('oficio', 'certidao', 'requerimento_administrativo', 'convite', 'mala_direta', 'outro')),
  assunto      text NOT NULL CHECK (assunto <> ''),
  corpo        text NOT NULL CHECK (corpo <> ''),         -- conteudo RENDERIZADO (merge aplicado); nao-vazio
  dados_merge  jsonb,                                     -- snapshot dos fatos mesclados (auditoria/reproducao)
  estado       text NOT NULL DEFAULT 'rascunho' CHECK (estado IN ('rascunho', 'emitido')),
  -- vinculo opcional a' entrada do Protocolo Geral (F3.9a), quando o documento e' protocolado.
  protocolo_geral_id uuid,
  emitido_em   timestamptz,
  emitido_por  uuid,
  -- transversais (§22.4.3 disc.1); estado/corpo mutam enquanto rascunho -> updated_*/lock_version
  origem    text NOT NULL DEFAULT 'nativa',
  origem_ref text,
  origem_importado_em timestamptz,
  lote_id   uuid,
  efetivado_em timestamptz,
  lock_version integer NOT NULL DEFAULT 0,
  created_by uuid,
  updated_by uuid,
  criado_em    timestamptz NOT NULL DEFAULT now(),
  atualizado_em timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  FOREIGN KEY (ente_id, modelo_id) REFERENCES legislativo.documento_modelo (ente_id, id),
  FOREIGN KEY (ente_id, protocolo_geral_id) REFERENCES legislativo.protocolo_geral (ente_id, id),
  -- emitido exige a marca da emissao: data E autor (autoria do artefato legal; review F3.9b DB-MAJOR).
  CONSTRAINT documento_emitido_tem_marca
    CHECK (estado <> 'emitido' OR (emitido_em IS NOT NULL AND emitido_por IS NOT NULL)),
  CONSTRAINT documento_staging_valido CHECK (lote_id IS NOT NULL OR efetivado_em IS NOT NULL)
);
--;;
-- FK sem UNIQUE -> indices proprios (lado-filho). protocolo_geral_id e' majoritariamente NULL -> parcial.
CREATE INDEX IF NOT EXISTS idx_documento_modelo ON legislativo.documento (ente_id, modelo_id);
--;;
CREATE INDEX IF NOT EXISTS idx_documento_protocolo
  ON legislativo.documento (ente_id, protocolo_geral_id) WHERE protocolo_geral_id IS NOT NULL;
--;;
CREATE INDEX IF NOT EXISTS idx_documento_estado ON legislativo.documento (ente_id, estado);
--;;
CREATE INDEX IF NOT EXISTS idx_documento_staging ON legislativo.documento (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE legislativo.documento ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.documento FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON legislativo.documento;
--;;
CREATE POLICY tenant_isolation ON legislativo.documento
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE ON legislativo.documento TO oplenario_app;
--;;
-- imutabilidade (b): emitido trava o conteudo (artefato). Rascunho muta (o servidor revisa/edita).
-- CAVEAT (carry import/fundacao #2, review F3.9b DB-MENOR): importar um documento JA 'emitido' via staging
-- (lote_id + efetivado_em NULL) e depois efetivar o lote (UPDATE efetivado_em=now) dispararia este trigger
-- com OLD.estado='emitido' -> excecao, e a linha ficaria presa. Import de terminais deve inserir com
-- efetivado_em=now direto (sem passar por lote em estado terminal). Vale p/ todas as tabelas trava-terminal.
CREATE TRIGGER trg_documento_imut_estado
  BEFORE UPDATE ON legislativo.documento
  FOR EACH ROW EXECUTE FUNCTION shared.imut_trava_estado_terminal('emitido');
