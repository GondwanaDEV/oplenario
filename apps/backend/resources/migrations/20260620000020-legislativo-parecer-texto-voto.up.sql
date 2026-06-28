-- F3.6b: modulo legislativo, eixo F (§22.4) — texto do parecer + votos divergentes.
-- (1) parecer_texto_versao: MESMA estrategia do eixo B (proposicao_texto_versao, mig 0015) — conteudo
--     append-only (trigger congela texto/uri/origem/hash; so estado_versao muta), hibrido inline/URI 32KB,
--     promocao auditada rascunho->vigente que reaponta pareceres.texto_vigente_versao_id. NAO-particionada
--     (cardinalidade moderada como pareceres; o pai NAO e' particionado, ao contrario de proposicoes).
-- (2) parecer_voto_divergente: tabela auxiliar APPEND-ONLY PURO (nivel a) — o voto vencido do membro da
--     comissao, registrado e NUNCA alterado/apagado (Inv.10). Sem lock_version/updated_* (nao muta).

-- ============================ TEXTO DO PARECER (versionamento, eixo B aplicado) ============================
CREATE TABLE IF NOT EXISTS legislativo.parecer_texto_versao (
  ente_id       uuid    NOT NULL,
  id            uuid    NOT NULL DEFAULT gen_random_uuid(),
  parecer_id    uuid    NOT NULL,
  numero_versao integer NOT NULL,                         -- ordinal LOCAL por parecer (1,2,3…)
  origem_versao text NOT NULL CHECK (origem_versao IN ('redacao', 'substitutivo', 'importacao_legado')),
  origem_ref  uuid,                                       -- guard ref (sem FK)
  origem_tipo text,
  estado_versao text NOT NULL DEFAULT 'rascunho' CHECK (estado_versao IN
    ('rascunho', 'vigente', 'superada', 'arquivada')),
  formato      text NOT NULL DEFAULT 'markdown',
  texto_inline text,
  conteudo_uri text,
  hash_conteudo text,
  origem        text NOT NULL DEFAULT 'nativa',
  origem_importado_em timestamptz,
  lote_id       uuid,
  efetivado_em  timestamptz,
  lock_version  integer NOT NULL DEFAULT 0,
  created_by    uuid,
  updated_by    uuid,
  criado_em     timestamptz NOT NULL DEFAULT now(),
  atualizado_em timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  UNIQUE (ente_id, parecer_id, numero_versao),
  FOREIGN KEY (ente_id, parecer_id) REFERENCES legislativo.pareceres (ente_id, id),
  CONSTRAINT parecer_texto_conteudo_xor CHECK ((texto_inline IS NOT NULL) <> (conteudo_uri IS NOT NULL)),
  CONSTRAINT parecer_texto_inline_32kb  CHECK (texto_inline IS NULL OR octet_length(texto_inline) <= 32768)
);
--;;
-- no maximo UMA versao 'vigente' por parecer (consistencia com o pointer texto_vigente_versao_id).
CREATE UNIQUE INDEX IF NOT EXISTS uq_parecer_texto_uma_vigente
  ON legislativo.parecer_texto_versao (ente_id, parecer_id) WHERE estado_versao = 'vigente';
--;;
-- FK parecer_id sempre indexada (convencao do projeto; cobre tambem "versoes do parecer").
CREATE INDEX IF NOT EXISTS idx_parecer_texto_parecer ON legislativo.parecer_texto_versao (ente_id, parecer_id);
--;;
CREATE INDEX IF NOT EXISTS idx_parecer_texto_staging ON legislativo.parecer_texto_versao (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE legislativo.parecer_texto_versao ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.parecer_texto_versao FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON legislativo.parecer_texto_versao;
--;;
CREATE POLICY tenant_isolation ON legislativo.parecer_texto_versao
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE ON legislativo.parecer_texto_versao TO oplenario_app;
--;;
-- imutabilidade do CONTEUDO (§22.4.3 c): so estado_versao (+ carimbos/lock/efetivacao) muta; mexer no
-- conteudo/identidade da versao lanca. Espelha legislativo.texto_versao_conteudo_imutavel (mig 0015).
CREATE OR REPLACE FUNCTION legislativo.parecer_texto_conteudo_imutavel() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.parecer_id    IS DISTINCT FROM OLD.parecer_id
     OR NEW.numero_versao IS DISTINCT FROM OLD.numero_versao
     OR NEW.origem_versao IS DISTINCT FROM OLD.origem_versao
     OR NEW.origem_ref    IS DISTINCT FROM OLD.origem_ref
     OR NEW.origem_tipo   IS DISTINCT FROM OLD.origem_tipo
     OR NEW.formato       IS DISTINCT FROM OLD.formato
     OR NEW.texto_inline  IS DISTINCT FROM OLD.texto_inline
     OR NEW.conteudo_uri  IS DISTINCT FROM OLD.conteudo_uri
     OR NEW.hash_conteudo IS DISTINCT FROM OLD.hash_conteudo
     OR NEW.origem        IS DISTINCT FROM OLD.origem
     OR NEW.origem_importado_em IS DISTINCT FROM OLD.origem_importado_em
     OR NEW.ente_id       IS DISTINCT FROM OLD.ente_id THEN
    RAISE EXCEPTION 'versao de texto do parecer e append-only no conteudo: so estado_versao muda (versao=%/%)',
      OLD.parecer_id, OLD.numero_versao USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END;
$$;
--;;
CREATE TRIGGER trg_parecer_texto_conteudo_imutavel
  BEFORE UPDATE ON legislativo.parecer_texto_versao
  FOR EACH ROW EXECUTE FUNCTION legislativo.parecer_texto_conteudo_imutavel();
--;;
-- ============================ VOTOS DIVERGENTES (auxiliar, append-only puro) ============================
CREATE TABLE IF NOT EXISTS legislativo.parecer_voto_divergente (
  ente_id      uuid NOT NULL,
  id           uuid NOT NULL DEFAULT gen_random_uuid(),
  parecer_id   uuid NOT NULL,
  vereador_id  uuid NOT NULL,                             -- guard ref (sem FK cross-schema)
  voto         text NOT NULL,                             -- vocabulario regimental ABERTO (sem CHECK, §22.4.4)
  justificativa text,
  -- transversais de append-only PURO: sem updated_*/lock_version (nunca muta)
  origem       text NOT NULL DEFAULT 'nativa',
  origem_importado_em timestamptz,
  lote_id      uuid,
  efetivado_em timestamptz,
  criado_em    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  FOREIGN KEY (ente_id, parecer_id) REFERENCES legislativo.pareceres (ente_id, id)
);
--;;
CREATE INDEX IF NOT EXISTS idx_voto_divergente_parecer ON legislativo.parecer_voto_divergente (ente_id, parecer_id);
--;;
CREATE INDEX IF NOT EXISTS idx_voto_divergente_staging ON legislativo.parecer_voto_divergente (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE legislativo.parecer_voto_divergente ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.parecer_voto_divergente FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON legislativo.parecer_voto_divergente;
--;;
CREATE POLICY tenant_isolation ON legislativo.parecer_voto_divergente
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- append-only PURO (nivel a): SELECT/INSERT; sem UPDATE/DELETE (Inv.10 — voto vencido registrado nunca muda).
GRANT SELECT, INSERT ON legislativo.parecer_voto_divergente TO oplenario_app;
--;;
CREATE TRIGGER trg_voto_divergente_append_only
  BEFORE UPDATE OR DELETE ON legislativo.parecer_voto_divergente
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
