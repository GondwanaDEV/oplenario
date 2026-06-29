-- F4.2a: modulo SESSOES, eixo B do §22.6 — PAUTA (camada viva). Tres tabelas:
--   pauta_sessao     : container 1:1 com a sessao (UNIQUE por sessao). FK same-schema -> sessoes.sessao.
--   pauta_item       : item da pauta, MUTAVEL (ordem/ativo durante a execucao). FASE como ATRIBUTO; tipo de
--                      item com FK DECLARATIVA por tipo (proposicao_id XOR texto_descricao) — descartado
--                      polimorfismo (conjunto pequeno e estavel). `proposicao_id` e' forward-ref (uuid, sem FK
--                      cross-schema p/ legislativo, §22.10). Remocao intra-sessao = ativo=false (NUNCA DELETE,
--                      Inv.10); estado EXECUTADO (despachado/votado) e' emergente (disc.5 B.1), nao coluna aqui.
--   pauta_alteracao  : log APPEND-ONLY das mudancas intra-sessao (inclusao|exclusao|inversao|retirada_pedido_autor).
-- NAO-particionadas (cardinalidade moderada por ente).

-- ============================ pauta_sessao (container 1:1) ============================
CREATE TABLE IF NOT EXISTS sessoes.pauta_sessao (
  ente_id    uuid NOT NULL,
  id         uuid NOT NULL DEFAULT gen_random_uuid(),
  sessao_id  uuid NOT NULL,
  -- container de IDENTIDADE (1:1 com a sessao). O versionamento canonico vive em pauta_sessao_versao (F4.2b)
  -- e o estado EXECUTADO e' emergente (disc.5) — por isso o container e' INSERT-only (GRANT sem UPDATE; sem
  -- lock_version/updated_*; review F4.2a DB-M1). lote_id/efetivado_em ficam por consistencia de RLS (o INSERT
  -- live ja crava efetivado_em=now(); sem _staging_valido CHECK pois insert-only nao faz efetivacao em 2 fases,
  -- mesmo motivo do C1 abaixo / legislativo.votos).
  origem    text NOT NULL DEFAULT 'nativa',
  origem_ref text,
  origem_importado_em timestamptz,
  lote_id   uuid,
  efetivado_em timestamptz,
  created_by uuid,
  criado_em    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  UNIQUE (ente_id, sessao_id),                            -- 1:1 com a sessao
  FOREIGN KEY (ente_id, sessao_id) REFERENCES sessoes.sessao (ente_id, id)  -- same-schema same-tenant
);
--;;
CREATE INDEX IF NOT EXISTS idx_pauta_sessao_staging ON sessoes.pauta_sessao (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE sessoes.pauta_sessao ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE sessoes.pauta_sessao FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON sessoes.pauta_sessao;
--;;
CREATE POLICY tenant_isolation ON sessoes.pauta_sessao
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT ON sessoes.pauta_sessao TO oplenario_app;

-- ============================ pauta_item (mutavel) ============================
--;;
CREATE TABLE IF NOT EXISTS sessoes.pauta_item (
  ente_id  uuid NOT NULL,
  id       uuid NOT NULL DEFAULT gen_random_uuid(),
  pauta_sessao_id uuid NOT NULL,
  fase     text NOT NULL CHECK (fase IN
    ('expediente', 'grande_expediente', 'ordem_do_dia', 'explicacoes_pessoais', 'tribuna_livre_cidadao')),
  tipo_item text NOT NULL CHECK (tipo_item IN ('proposicao', 'leitura', 'comunicado', 'homenagem')),
  proposicao_id   uuid,                                   -- forward-ref a legislativo.proposicoes (sem FK, §22.10)
  texto_descricao text,                                   -- fallback p/ itens nao-proposicao
  ordem    integer NOT NULL,                              -- ordem corrente na pauta (sort hint; reordenavel)
  ativo    boolean NOT NULL DEFAULT true,                 -- remocao intra-sessao = false (nunca DELETE)
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
  FOREIGN KEY (ente_id, pauta_sessao_id) REFERENCES sessoes.pauta_sessao (ente_id, id),
  -- FK DECLARATIVA por tipo: proposicao exige proposicao_id e proibe descricao; os demais o inverso.
  CONSTRAINT pauta_item_proposicao_coerente CHECK (
    (tipo_item = 'proposicao' AND proposicao_id IS NOT NULL AND texto_descricao IS NULL)
    OR (tipo_item <> 'proposicao' AND proposicao_id IS NULL AND texto_descricao IS NOT NULL
        AND length(trim(texto_descricao)) > 0)),
  CONSTRAINT pauta_item_staging_valido CHECK (lote_id IS NOT NULL OR efetivado_em IS NOT NULL)
);
--;;
-- hot-path: listar itens (ativos) de uma pauta em ordem.
CREATE INDEX IF NOT EXISTS idx_pauta_item_pauta ON sessoes.pauta_item (ente_id, pauta_sessao_id, ordem) WHERE ativo;
--;;
CREATE INDEX IF NOT EXISTS idx_pauta_item_staging ON sessoes.pauta_item (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE sessoes.pauta_item ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE sessoes.pauta_item FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON sessoes.pauta_item;
--;;
CREATE POLICY tenant_isolation ON sessoes.pauta_item
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- UPDATE permitido (ordem/ativo mutam); sem DELETE (remocao e' soft, Inv.10).
GRANT SELECT, INSERT, UPDATE ON sessoes.pauta_item TO oplenario_app;

-- ============================ pauta_alteracao (append-only) ============================
--;;
CREATE TABLE IF NOT EXISTS sessoes.pauta_alteracao (
  ente_id  uuid NOT NULL,
  id       uuid NOT NULL DEFAULT gen_random_uuid(),
  pauta_sessao_id uuid NOT NULL,
  pauta_item_id   uuid,                                   -- item afetado (nullable; FK MATCH SIMPLE)
  tipo     text NOT NULL CHECK (tipo IN ('inclusao', 'exclusao', 'inversao', 'retirada_pedido_autor')),
  justificativa text,
  detalhe  jsonb,                                         -- ex.: {"de_ordem":2,"para_ordem":0}
  origem    text NOT NULL DEFAULT 'nativa',
  origem_ref text,                                        -- correlaciona id externo no import (review m4)
  origem_importado_em timestamptz,
  lote_id   uuid,
  efetivado_em timestamptz,
  created_by uuid,
  registrado_em timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  FOREIGN KEY (ente_id, pauta_sessao_id) REFERENCES sessoes.pauta_sessao (ente_id, id),
  FOREIGN KEY (ente_id, pauta_item_id)   REFERENCES sessoes.pauta_item   (ente_id, id),
  -- toda alteracao opera sobre um item, EXCETO inversao (pode ser reordenacao geral). (review m1)
  CONSTRAINT pauta_alteracao_item_requerido CHECK (tipo = 'inversao' OR pauta_item_id IS NOT NULL)
  -- SEM _staging_valido CHECK (review C1): append-only puro (trigger barra UPDATE) nao faz efetivacao em 2
  -- fases — o CHECK so criaria trap de linha-fantasma no import. Mesmo padrao de legislativo.votos. O INSERT
  -- live ja crava efetivado_em=now().
);
--;;
CREATE INDEX IF NOT EXISTS idx_pauta_alteracao_pauta ON sessoes.pauta_alteracao (ente_id, pauta_sessao_id, registrado_em);
--;;
-- FK pauta_item_id: indexa o lado referenciador p/ auditoria "quais alteracoes afetaram o item X?" (review m2).
CREATE INDEX IF NOT EXISTS idx_pauta_alteracao_item ON sessoes.pauta_alteracao (ente_id, pauta_item_id) WHERE pauta_item_id IS NOT NULL;
--;;
CREATE INDEX IF NOT EXISTS idx_pauta_alteracao_staging ON sessoes.pauta_alteracao (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE sessoes.pauta_alteracao ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE sessoes.pauta_alteracao FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON sessoes.pauta_alteracao;
--;;
CREATE POLICY tenant_isolation ON sessoes.pauta_alteracao
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT ON sessoes.pauta_alteracao TO oplenario_app;
--;;
CREATE TRIGGER trg_pauta_alteracao_append_only
  BEFORE UPDATE OR DELETE ON sessoes.pauta_alteracao
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
