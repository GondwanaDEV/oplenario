-- F3.2: modulo legislativo, eixo B (§22.4) — versionamento de texto da proposicao.
-- IMUTABILIDADE (reconcilia §22.4.3 niveis a/c): o CONTEUDO da versao e' append-only (texto/uri/numero/
-- origem/hash congelados por trigger; sem DELETE jamais); o `estado_versao` e' a UNICA mutacao controlada
-- (fluxo de promocao auditado rascunho->vigente->superada, §22.4 disc.3). A "promocao" canonica e' o UPDATE
-- de legislativo.proposicoes.texto_vigente_versao_id (§22.4 eixo B). VOLUMOSA -> hash-particao por ente_id.

CREATE TABLE IF NOT EXISTS legislativo.proposicao_texto_versao (
  ente_id       uuid    NOT NULL,
  id            uuid    NOT NULL DEFAULT gen_random_uuid(),
  proposicao_id uuid    NOT NULL,
  numero_versao integer NOT NULL,                         -- ordinal LOCAL por proposicao (1,2,3…)
  -- proveniencia (eixo B): de onde a versao veio + ref polimorfica ao gatilho
  origem_versao text NOT NULL CHECK (origem_versao IN
    ('protocolo','substitutivo','aplicacao_emenda','redacao_final','promulgacao','importacao_legado')),
  origem_ref  uuid,                                       -- emenda_id | sessao_id | … (guard ref, sem FK)
  origem_tipo text,                                       -- descreve o alvo de origem_ref
  -- ciclo de vida (a UNICA coluna mutavel; promocao auditada)
  estado_versao text NOT NULL DEFAULT 'rascunho' CHECK (estado_versao IN
    ('rascunho','vigente','superada','arquivada')),
  -- conteudo: hibrido inline/URI (XOR), inline so ate o threshold de 32KB
  formato      text NOT NULL DEFAULT 'markdown',
  texto_inline text,
  conteudo_uri text,                                      -- ponteiro p/ objeto_store (>32KB)
  hash_conteudo text,
  -- transversais (estado muta -> mantem updated_*/lock_version, ao contrario de append-only puro)
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
  UNIQUE (ente_id, proposicao_id, numero_versao),
  -- integridade same-tenant declarativa (ambas particionadas por ente_id; FK inclui ente_id)
  FOREIGN KEY (ente_id, proposicao_id) REFERENCES legislativo.proposicoes (ente_id, id),
  CONSTRAINT texto_versao_conteudo_xor  CHECK ((texto_inline IS NOT NULL) <> (conteudo_uri IS NOT NULL)),
  CONSTRAINT texto_versao_inline_32kb   CHECK (texto_inline IS NULL OR octet_length(texto_inline) <= 32768)
) PARTITION BY HASH (ente_id);
--;;
CREATE TABLE IF NOT EXISTS legislativo.proposicao_texto_versao_p0 PARTITION OF legislativo.proposicao_texto_versao FOR VALUES WITH (MODULUS 8, REMAINDER 0);
--;;
CREATE TABLE IF NOT EXISTS legislativo.proposicao_texto_versao_p1 PARTITION OF legislativo.proposicao_texto_versao FOR VALUES WITH (MODULUS 8, REMAINDER 1);
--;;
CREATE TABLE IF NOT EXISTS legislativo.proposicao_texto_versao_p2 PARTITION OF legislativo.proposicao_texto_versao FOR VALUES WITH (MODULUS 8, REMAINDER 2);
--;;
CREATE TABLE IF NOT EXISTS legislativo.proposicao_texto_versao_p3 PARTITION OF legislativo.proposicao_texto_versao FOR VALUES WITH (MODULUS 8, REMAINDER 3);
--;;
CREATE TABLE IF NOT EXISTS legislativo.proposicao_texto_versao_p4 PARTITION OF legislativo.proposicao_texto_versao FOR VALUES WITH (MODULUS 8, REMAINDER 4);
--;;
CREATE TABLE IF NOT EXISTS legislativo.proposicao_texto_versao_p5 PARTITION OF legislativo.proposicao_texto_versao FOR VALUES WITH (MODULUS 8, REMAINDER 5);
--;;
CREATE TABLE IF NOT EXISTS legislativo.proposicao_texto_versao_p6 PARTITION OF legislativo.proposicao_texto_versao FOR VALUES WITH (MODULUS 8, REMAINDER 6);
--;;
CREATE TABLE IF NOT EXISTS legislativo.proposicao_texto_versao_p7 PARTITION OF legislativo.proposicao_texto_versao FOR VALUES WITH (MODULUS 8, REMAINDER 7);
--;;
-- no maximo UMA versao 'vigente' por proposicao (mantem o estado_versao consistente com o pointer).
CREATE UNIQUE INDEX IF NOT EXISTS uq_texto_versao_uma_vigente
  ON legislativo.proposicao_texto_versao (ente_id, proposicao_id) WHERE estado_versao = 'vigente';
--;;
-- staging das nao-efetivadas (import de legado, fundacao #2).
CREATE INDEX IF NOT EXISTS idx_texto_versao_staging ON legislativo.proposicao_texto_versao (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE legislativo.proposicao_texto_versao ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.proposicao_texto_versao FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON legislativo.proposicao_texto_versao;
--;;
CREATE POLICY tenant_isolation ON legislativo.proposicao_texto_versao
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL
              OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- UPDATE permitido (so p/ estado_versao via trigger abaixo); sem DELETE (Inv.10).
GRANT SELECT, INSERT, UPDATE ON legislativo.proposicao_texto_versao TO oplenario_app;
--;;
-- imutabilidade do CONTEUDO (§22.4.3 c): qualquer UPDATE que mude conteudo/identidade da versao lanca;
-- so estado_versao (+ carimbos/lock/efetivacao) muta — o fluxo de promocao auditado.
CREATE OR REPLACE FUNCTION legislativo.texto_versao_conteudo_imutavel() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.proposicao_id IS DISTINCT FROM OLD.proposicao_id
     OR NEW.numero_versao IS DISTINCT FROM OLD.numero_versao
     OR NEW.origem_versao IS DISTINCT FROM OLD.origem_versao
     OR NEW.origem_ref    IS DISTINCT FROM OLD.origem_ref
     OR NEW.origem_tipo   IS DISTINCT FROM OLD.origem_tipo
     OR NEW.formato       IS DISTINCT FROM OLD.formato
     OR NEW.texto_inline  IS DISTINCT FROM OLD.texto_inline
     OR NEW.conteudo_uri  IS DISTINCT FROM OLD.conteudo_uri
     OR NEW.hash_conteudo IS DISTINCT FROM OLD.hash_conteudo
     OR NEW.ente_id       IS DISTINCT FROM OLD.ente_id THEN
    RAISE EXCEPTION 'versao de texto e append-only no conteudo: so estado_versao muda (versao=%/%)',
      OLD.proposicao_id, OLD.numero_versao USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END;
$$;
--;;
CREATE TRIGGER trg_texto_versao_conteudo_imutavel
  BEFORE UPDATE ON legislativo.proposicao_texto_versao
  FOR EACH ROW EXECUTE FUNCTION legislativo.texto_versao_conteudo_imutavel();
