-- F4.2b: modulo SESSOES, eixo B do §22.6 — VERSIONAMENTO CANONICO da pauta.
--   pauta_sessao_versao : snapshot APPEND-ONLY da pauta num instante. A camada viva (pauta_item/
--                         pauta_alteracao, F4.2a) MUTA durante a execucao; a versao CONGELA a pauta em jsonb
--                         (itens ativos em ordem) = o que o portal do cidadao cita e a prova institucional.
--   numero_versao : sequencial LOCAL por pauta (1,2,3...). Aqui o numero E' identidade (diferente de
--                   pauta_item.ordem, que e' sort hint) — por isso UNIQUE: a corrida vira violacao de
--                   constraint, nao dup silenciosa.
-- NAO-particionada (cardinalidade baixa: poucas publicacoes por pauta).

CREATE TABLE IF NOT EXISTS sessoes.pauta_sessao_versao (
  ente_id  uuid NOT NULL,
  id       uuid NOT NULL DEFAULT gen_random_uuid(),
  pauta_sessao_id uuid NOT NULL,
  numero_versao integer NOT NULL,                         -- sequencial local por pauta (identidade)
  tipo_versao text NOT NULL CHECK (tipo_versao IN
    ('publicacao_inicial', 'republicacao', 'execucao_final')),
  publica  boolean NOT NULL DEFAULT true,                 -- visivel no portal do cidadao?
  snapshot jsonb NOT NULL,                                -- itens ativos congelados [{id,fase,tipo_item,...,ordem}]
  justificativa text,                                     -- ex.: por que republicou
  origem    text NOT NULL DEFAULT 'nativa',
  origem_ref text,
  origem_importado_em timestamptz,
  lote_id   uuid,
  efetivado_em timestamptz,
  created_by uuid,
  publicado_em timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  UNIQUE (ente_id, pauta_sessao_id, numero_versao),       -- numero_versao e' identidade local
  FOREIGN KEY (ente_id, pauta_sessao_id) REFERENCES sessoes.pauta_sessao (ente_id, id),
  -- republicacao exige justificativa (defesa-em-profundidade; espelha sessao_nao_realizada_tem_motivo da 0026).
  CONSTRAINT pauta_versao_republicacao_justificativa CHECK (
    tipo_versao <> 'republicacao' OR (justificativa IS NOT NULL AND length(trim(justificativa)) > 0))
  -- SEM _staging_valido CHECK: append-only puro (trigger barra UPDATE) nao faz efetivacao em 2 fases — o CHECK
  -- so criaria trap de linha-fantasma no import. Mesmo padrao de pauta_alteracao / legislativo.votos. O INSERT
  -- live crava efetivado_em=now().
);
--;;
-- hot-path listar/proxima/FK (prefix em ente_id,pauta_sessao_id + ORDER BY numero_versao) ja e' coberto pelo
-- indice implicito do UNIQUE acima — sem indice redundante (review F4.2b DB-MAJOR). So o parcial publico abaixo.
CREATE INDEX IF NOT EXISTS idx_pauta_versao_publica ON sessoes.pauta_sessao_versao (ente_id, pauta_sessao_id, numero_versao) WHERE publica;
--;;
CREATE INDEX IF NOT EXISTS idx_pauta_versao_staging ON sessoes.pauta_sessao_versao (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE sessoes.pauta_sessao_versao ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE sessoes.pauta_sessao_versao FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON sessoes.pauta_sessao_versao;
--;;
CREATE POLICY tenant_isolation ON sessoes.pauta_sessao_versao
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT ON sessoes.pauta_sessao_versao TO oplenario_app;
--;;
CREATE TRIGGER trg_pauta_versao_append_only
  BEFORE UPDATE OR DELETE ON sessoes.pauta_sessao_versao
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
